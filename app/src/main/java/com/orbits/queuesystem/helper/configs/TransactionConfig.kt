package com.orbits.queuesystem.helper.configs

import android.content.Context
import com.orbits.queuesystem.helper.Extensions.asInt
import com.orbits.queuesystem.helper.Extensions.asString
import com.orbits.queuesystem.helper.database.TransactionDataDbModel
import com.orbits.queuesystem.mvvm.main.model.TransactionListDataModel

object TransactionConfig {

    fun Context.parseInTransactionDbModel(
        model: TransactionListDataModel?,
        id: String,
    ): TransactionDataDbModel {
        return TransactionDataDbModel(
            id = id.asInt(),
            serviceId = model?.serviceId ?: "",
            entityID = model?.id ?: "",
            keypadToken = model?.keypadToken,
            counterId = model?.counterId ?: "",
            counterType = model?.counterId ?: "",
            serviceAssign = model?.serviceAssign,
            token = model?.token,
            ticketToken = model?.ticketToken,
            issueTime = model?.issueTime,
            startKeypadTime = model?.startKeypadTime,
            endKeypadTime = model?.endKeypadTime,
            status = model?.status
        )
    }


    fun Context.parseInTransactionModelArraylist(it: ArrayList<TransactionDataDbModel?>?): ArrayList<TransactionListDataModel?> {
        val items = ArrayList<TransactionListDataModel?>()
        if (it != null) {
            for (i in 0 until it.size) {
                val a = it[i]

                val item = TransactionListDataModel(
                    id = a?.id.asString(),
                    serviceId = a?.serviceId,
                    counterId = a?.counterId ?: "",
                    entityID = a?.entityID ?: "",
                    serviceAssign = a?.serviceAssign ?: "",
                    token = a?.token ?: "",
                    ticketToken = a?.ticketToken ?: "",
                    keypadToken = a?.keypadToken ?: "",
                    issueTime = a?.issueTime ?: "",
                    startKeypadTime = a?.startKeypadTime ?: "",
                    endKeypadTime = a?.endKeypadTime ?: "",
                    status = a?.status ?: "",

                )


                items.add(item)
            }
        }
        return items
    }
}