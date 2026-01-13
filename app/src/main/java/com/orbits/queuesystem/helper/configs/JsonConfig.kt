package com.orbits.queuesystem.helper.configs

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.orbits.queuesystem.helper.Constants
import com.orbits.queuesystem.helper.Extensions.asString
import com.orbits.queuesystem.helper.database.CounterDataDbModel
import com.orbits.queuesystem.helper.database.LocalDB.getAllCounterFromDB
import com.orbits.queuesystem.helper.database.LocalDB.getAllServiceFromDB
import com.orbits.queuesystem.helper.database.LocalDB.getAllTransactionFromDB
import com.orbits.queuesystem.helper.database.LocalDB.getAllUserFromDB
import com.orbits.queuesystem.helper.database.LocalDB.getCurrentServiceToken
import com.orbits.queuesystem.helper.database.ServiceDataDbModel
import com.orbits.queuesystem.helper.database.TransactionDataDbModel

object JsonConfig {

    val gson = Gson()

    fun Context.createJsonData(): JsonObject {
        val itemsArray = JsonArray().apply {
            val services = getAllServiceFromDB()
            services?.forEach { service ->
                add(service?.toJsonObject())
            }
        }

        val counterArray = JsonArray().apply {
            val counters = getAllCounterFromDB()
            counters?.forEach { counter ->
                add(counter?.toCounterJsonObject())
            }
        }

        return JsonObject().apply {
            add("items", itemsArray)
            add("counters", counterArray)
        }
    }


    fun Context.createDisplayJsonData(id:String): JsonObject {
        val itemsArray = JsonArray().apply {
            val services = getAllServiceFromDB()
            services?.forEach { service ->
                add(service?.toJsonObject())
            }
        }

        val counterArray = JsonArray().apply {
            val counters = getAllCounterFromDB()
            counters?.forEach { counter ->
                add(counter?.toCounterJsonObject())
            }
        }

        return JsonObject().apply {
            add("items", itemsArray)
            add("counters", counterArray)
            addProperty("displayId", id)
        }
    }


    fun Context.createMasterDisplayJsonData(transactions:ArrayList<TransactionDataDbModel?>?): JsonObject {
        val itemsArray = JsonArray().apply {
            val services = getAllServiceFromDB()
            services?.forEach { service ->
                add(service?.toJsonObject())
            }
        }

        val counterArray = JsonArray().apply {
            val counters = getAllCounterFromDB()
            counters?.forEach { counter ->
                add(counter?.toCounterJsonObject())
            }
        }

        val transactionArray = JsonArray().apply {
            transactions?.forEach { transaction ->
                add(transaction?.toTransactionJsonObject())
            }
        }

        return JsonObject().apply {
            add("items", itemsArray)
            add("counters", counterArray)
            add("transactions", transactionArray)
        }
    }

    fun Context.createMasterDisplayJsonDataWithMsg(transactions:ArrayList<TransactionDataDbModel?>?): JsonObject {
        val transactionArray = JsonArray().apply {
            transactions?.forEach { transaction ->
                add(transaction?.toTransactionJsonObject())
            }
        }

        return JsonObject().apply {
            add("transactions", transactionArray)
            addProperty("fromNext", "fromNext")
        }
    }


    fun Context.createTransactionsJsonData(transactions: ArrayList<TransactionDataDbModel?>?): JsonObject {
        val transactionCount = transactions?.size ?: 0
        Log.i("deepuyadav", "createTransactionsJsonData: $transactions")
        return JsonObject().apply {
            addProperty("transactionCount", transactionCount) // Add the size as a property
        }
    }




    fun Context.createServiceJsonDataWithModel(serviceId : String,transactionModel: TransactionDataDbModel): JsonObject {
        val model = getAllServiceFromDB()?.find { it?.entityID == serviceId }
        println("here is start token ${getCurrentServiceToken(model?.id.asString())}")
        val jsonModel = gson.toJson(transactionModel)
        return JsonObject().apply {
            addProperty("startToken", model?.tokenStart)
            addProperty("endToken", model?.tokenEnd)
            addProperty("serviceName", model?.serviceName)
            addProperty("serviceName", model?.serviceName)
            add(Constants.TRANSACTION,  gson.fromJson(jsonModel, JsonObject::class.java))
        }
    }


    fun Context.createServiceJsonDataWithTransaction(transactionModel: Any?): JsonObject {
        println("here is transaction model ${transactionModel}")
        val jsonModel = gson.toJson(transactionModel)
        return JsonObject().apply {
            if (transactionModel != null) add(Constants.TRANSACTION,  gson.fromJson(jsonModel, JsonObject::class.java))

        }
    }

    fun Context.createReconnectionJsonDataWithTransaction(): JsonObject {
        return JsonObject().apply {
            addProperty("reconnected", "reconnected")

        }
    }


    fun Context.createNoTokensData(): JsonObject {
        return JsonObject().apply {
            addProperty("errorMessage", "No Token Available for the service")

        }
    }


    fun Context.createUserJsonData(userName:String): JsonObject {
        val model = getAllUserFromDB()?.find { it?.userName == userName }
        println("here is recorded user $model")
        return JsonObject().apply {
            addProperty("userName", model?.userName)
            addProperty("userId", model?.userId)
            addProperty("password", model?.password)
        }
    }



    fun ServiceDataDbModel.toJsonObject(): JsonObject {
        return JsonObject().apply {
            addProperty("id", entityID)
            addProperty("name", serviceName)
            addProperty("tokenStart", tokenStart)
            addProperty("tokenEnd", tokenEnd)
        }
    }

     fun CounterDataDbModel.toCounterJsonObject(): JsonObject {
        return JsonObject().apply {
            addProperty("id", entityID)
            addProperty("name", counterName)
            addProperty("counterType", counterType)
            addProperty("counterId", counterId)
            addProperty("serviceId", serviceId)
        }
    }


    fun TransactionDataDbModel.toTransactionJsonObject(): JsonObject {
        return JsonObject().apply {
            addProperty("id", entityID)
            addProperty("counterId", counterId)
            addProperty("counterType", counterType)
            addProperty("serviceId", serviceId)
            addProperty("keypadToken", keypadToken)
            addProperty("token", token)
        }
    }


}