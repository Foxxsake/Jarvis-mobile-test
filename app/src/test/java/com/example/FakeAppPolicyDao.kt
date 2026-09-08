package com.example

import com.example.data.AccessPolicy
import com.example.data.AppPolicy
import com.example.data.AppPolicyDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeAppPolicyDao : AppPolicyDao {
    private val policies = MutableStateFlow<Map<String, AppPolicy>>(emptyMap())

    override fun getAllPoliciesFlow(): Flow<List<AppPolicy>> {
        return policies.map { it.values.toList() }
    }

    override suspend fun getAllPolicies(): List<AppPolicy> {
        return policies.value.values.toList()
    }

    override suspend fun insertPolicy(policy: AppPolicy) {
        val current = policies.value.toMutableMap()
        current[policy.packageName] = policy
        policies.value = current
    }
}
