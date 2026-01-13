package com.orbits.queuesystem.helper.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface TransactionDao {


    @Insert
    fun addTransaction(vararg services: TransactionDataDbModel)

    @Query(
        "UPDATE TransactionDataDbModel" +
                " SET token =:token, status=:status ,startKeypadTime=:startKeypadTime,endKeypadTime=:endKeypadTime, counterId =:counterId, counterType =:counterType" +
                " WHERE id =:id"
    )
    fun updateTransactionOffline(
        token: String?,
        status:String?,
        id: String?,
        counterId: String?,
        counterType: String?,
        startKeypadTime: String?,
        endKeypadTime: String?,
    )

    @Query("SELECT * FROM TransactionDataDbModel WHERE token = :token AND serviceId = :serviceId ORDER BY issueTime DESC LIMIT 1")
    fun getTransactionByToken(token: String,serviceId: String): TransactionDataDbModel?

    @Query("SELECT * FROM TransactionDataDbModel WHERE serviceId = :serviceId")
    fun getTransactionsByServiceId(serviceId: String): List<TransactionDataDbModel?>


    @Query("SELECT token FROM TransactionDataDbModel WHERE issueTime=:issueTime")
    fun isTransactionPresent(issueTime: String?): Boolean

    @Query("SELECT * FROM TransactionDataDbModel")
    fun getAllTransaction(): List<TransactionDataDbModel?>

    @Query("SELECT * FROM TransactionDataDbModel WHERE status = 0 AND serviceId = :serviceId ORDER BY issueTime LIMIT 1")
    fun getTransactionByIssuedStatus(serviceId:String): TransactionDataDbModel?

    @Query("SELECT * FROM TransactionDataDbModel WHERE status = 0 AND counterId = :counterId ORDER BY issueTime LIMIT 1")
    fun getTransactionByCounter(counterId:String?): TransactionDataDbModel?

    @Query("SELECT * FROM TransactionDataDbModel WHERE status = 1 AND serviceId = :serviceId ORDER BY issueTime LIMIT 1")
    fun getTransactionByCalledStatus(serviceId:String): TransactionDataDbModel?

    @Query("SELECT * FROM TransactionDataDbModel WHERE status = 4 AND serviceId = :serviceId ORDER BY issueTime LIMIT 1")
    fun getTransactionByDisplayStatus(serviceId:String): TransactionDataDbModel?


    @Query("SELECT * FROM TransactionDataDbModel WHERE status = 1 AND counterId = :counterId ORDER BY issueTime DESC LIMIT 1")
    fun getLastTransactionByStatusOne(counterId: String): TransactionDataDbModel?



    @Query("UPDATE TransactionDataDbModel SET token = :tokenNo WHERE entityID = :entityID")
    fun updateTransactionToken(entityID: String, tokenNo: Int)

    @Query("UPDATE TransactionDataDbModel SET status = 5 WHERE status IN (0,1)")
    fun resetAllTransactions()

    @Query("UPDATE ServiceDataDbModel SET tokenNo = tokenStart")
    fun updateServiceToken()

    @Query("SELECT * FROM TransactionDataDbModel WHERE status = 1 ORDER BY startKeypadTime DESC LIMIT 5")
    fun getRequiredTransactions(): List<TransactionDataDbModel?>

    @Query("SELECT * FROM TransactionDataDbModel WHERE status = 1 AND serviceId IN (:serviceIds) ORDER BY startKeypadTime DESC LIMIT 5")
    fun getRequiredTransactionsWithServiceId(serviceIds: List<String?>?): List<TransactionDataDbModel?>


    @Query("SELECT * FROM TransactionDataDbModel WHERE status = 0 AND serviceId = :serviceId ")
    fun getAllTransactionCount(serviceId: String): List<TransactionDataDbModel?>

    @Query("SELECT * FROM TransactionDataDbModel WHERE status = 0 AND counterId = :counterId ")
    fun getAllTransactionCountCounter(counterId: String?): List<TransactionDataDbModel?>


}