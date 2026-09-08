package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AppPolicyDao {
    @Query("SELECT * FROM app_policies")
    fun getAllPoliciesFlow(): Flow<List<AppPolicy>>

    @Query("SELECT * FROM app_policies")
    suspend fun getAllPolicies(): List<AppPolicy>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPolicy(policy: AppPolicy)
}
