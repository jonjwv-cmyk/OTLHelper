package com.example.otlhelper.shared.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiResultTest {
    @Test
    fun okCarriesData() {
        val r: ApiResult<Int> = ApiResult.Ok(42)
        assertTrue(r.isOk)
        assertEquals(42, r.getOrNull())
        assertNull(r.errorCode)
    }

    @Test
    fun errCarriesCode() {
        val r: ApiResult<Int> = ApiResult.Err(code = "wrong_password", message = "bad creds")
        assertTrue(!r.isOk)
        assertEquals("wrong_password", r.errorCode)
        assertNull(r.getOrNull())
    }

    @Test
    fun mapTransformsOkOnly() {
        val ok: ApiResult<Int> = ApiResult.Ok(10)
        val mapped = ok.map { it * 2 }
        assertEquals(20, (mapped as ApiResult.Ok).data)

        val err: ApiResult<Int> = ApiResult.Err("x")
        val mappedErr = err.map { it * 2 }
        assertTrue(mappedErr is ApiResult.Err)
    }

    @Test
    fun getOrElseFallsBackOnError() {
        val ok: ApiResult<String> = ApiResult.Ok("yes")
        assertEquals("yes", ok.getOrElse { "fallback" })

        val err: ApiResult<String> = ApiResult.Err("e")
        assertEquals("fallback", err.getOrElse { "fallback" })
    }
}
