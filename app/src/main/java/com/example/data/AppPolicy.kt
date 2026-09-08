package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class AccessPolicy {
    ALLOW, ASK_EACH_TIME, BLOCK
}

@Entity(tableName = "app_policies")
data class AppPolicy(
    @PrimaryKey val packageName: String,
    val policy: AccessPolicy
)
