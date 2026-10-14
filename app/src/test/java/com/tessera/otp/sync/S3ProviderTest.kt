package com.tessera.otp.sync

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * S3 后端：签名算法、地址拼装与对象读写。
 *
 * 签名部分用独立实现（Python）算出的已知向量核对，避免"自己验自己"。
 * 读写部分起一个本地 HttpServer 假装对象存储，验证 404/幂等/请求头。
 */
class S3ProviderTest {

    // ---------- AWS Signature V4 ----------

    private val emptySha = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    private fun auth(
        method: String,
        uri: String,
        headers: Map<String, String>,
        region: String,
        ak: String = "AKIAIOSFODNN7EXAMPLE",
        sk: String = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
        date: String = "20261014T120000Z"
    ) = AwsV4.authorization(method, uri, headers, region, ak, sk, date)

    @Test
    fun `空载荷的 sha256 与 AWS 文档一致`() {
        assertEquals(emptySha, AwsV4.sha256Hex(ByteArray(0)))
    }

    @Test
    fun `路径风格 GET 的签名与参考实现一致`() {
        val headers = mapOf(
            "host" to "192.168.1.5:9000",
            "x-amz-content-sha256" to emptySha,
            "x-amz-date" to "20261014T120000Z"
        )
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20261014/us-east-1/s3/aws4_request, " +
                "SignedHeaders=host;x-amz-content-sha256;x-amz-date, " +
                "Signature=a9c737db13eecb422ed3ae835e8fee53d509e300a23bb89f9734a2f6d293f4a9",
            auth("GET", "/tessera-vault/backup/tessera.vault", headers, "us-east-1")
        )
    }

    @Test
    fun `带载荷的 PUT 签名随载荷变化`() {
        val body = "{\"v\":2,\"entries\":[]}".toByteArray()
        val payloadHash = AwsV4.sha256Hex(body)
        assertEquals("d878f55d0b40ff8fe87dbfabc00ad804180f9893fa726b0149ddc665310467ab", payloadHash)
        val headers = mapOf(
            "host" to "192.168.1.5:9000",
            "x-amz-content-sha256" to payloadHash,
            "x-amz-date" to "20261014T120000Z"
        )
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20261014/us-east-1/s3/aws4_request, " +
                "SignedHeaders=host;x-amz-content-sha256;x-amz-date, " +
                "Signature=defad362e88d1302168a111451a3688086f00fcac53dac377c1424f9616b8859",
            auth("PUT", "/tessera-vault/backup/tessera.vault", headers, "us-east-1")
        )
    }

    @Test
    fun `虚拟主机风格与会话令牌参与签名`() {
        val headers = mapOf(
            "host" to "mybucket.s3.us-west-2.amazonaws.com",
            "x-amz-content-sha256" to emptySha,
            "x-amz-date" to "20261014T120000Z",
            "x-amz-security-token" to "SESSIONTOKEN"
        )
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20261014/us-west-2/s3/aws4_request, " +
                "SignedHeaders=host;x-amz-content-sha256;x-amz-date;x-amz-security-token, " +
                "Signature=67fc2db2b1ac7ecb4262039c777e9d9712b5c07a700ce6570ee82a4b072dc8a3",
            auth("GET", "/backup/tessera.vault", headers, "us-west-2")
        )
    }

    @Test
    fun `DELETE 的签名与参考实现一致`() {
        val headers = mapOf(
            "host" to "minio.local:9000",
            "x-amz-content-sha256" to emptySha,
            "x-amz-date" to "20261014T120000Z"
        )
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=minioadmin/20261014/us-east-1/s3/aws4_request, " +
                "SignedHeaders=host;x-amz-content-sha256;x-amz-date, " +
                "Signature=e09ac0a3b61f70e54727f9f099a7ccb0a25ccbc59dd7b52fc058a3d76560a2d6",
            auth("DELETE", "/tessera-vault/tessera.vault", headers, "us-east-1", ak = "minioadmin", sk = "minioadmin")
        )
    }

    @Test
    fun `时间戳格式为 basic-iso8601`() {
        val formatted = AwsV4.amzDate(java.time.ZonedDateTime.of(2026, 10, 14, 12, 0, 0, 0, java.time.ZoneOffset.UTC))
        assertEquals("20261014T120000Z", formatted)
    }

    // ---------- 地址拼装 ----------

    @Test
    fun `默认虚拟主机风格把桶放到主机名前`() {
        val target = s3RequestTarget("https://s3.us-east-1.amazonaws.com", "mybucket", "backup/tessera.vault", false)
        assertEquals("https://mybucket.s3.us-east-1.amazonaws.com/backup/tessera.vault", target.url)
        assertEquals("/backup/tessera.vault", target.canonicalUri)
        assertEquals("mybucket.s3.us-east-1.amazonaws.com", target.host)
    }

    @Test
    fun `路径风格把桶放进路径（MinIO 等）`() {
        val target = s3RequestTarget("https://s3.us-east-1.amazonaws.com", "mybucket", "backup/tessera.vault", true)
        assertEquals("https://s3.us-east-1.amazonaws.com/mybucket/backup/tessera.vault", target.url)
        assertEquals("/mybucket/backup/tessera.vault", target.canonicalUri)
        assertEquals("s3.us-east-1.amazonaws.com", target.host)
    }

    @Test
    fun `省略协议时默认 https`() {
        val target = s3RequestTarget("192.168.1.5:9000", "vault", "tessera.vault", true)
        assertEquals("https://192.168.1.5:9000/vault/tessera.vault", target.url)
        assertEquals("192.168.1.5:9000", target.host)
    }

    @Test
    fun `默认端口从 Host 头里去掉`() {
        val target = s3RequestTarget("https://s3.example.com:443/", "vault", "tessera.vault", false)
        assertEquals("vault.s3.example.com", target.host)
        assertEquals("https://vault.s3.example.com/tessera.vault", target.url)
    }

    @Test
    fun `非默认端口保留在 Host 头里`() {
        val target = s3RequestTarget("http://127.0.0.1:9000", "vault", "tessera.vault", true)
        assertEquals("127.0.0.1:9000", target.host)
        assertEquals("http://127.0.0.1:9000/vault/tessera.vault", target.url)
    }

    @Test
    fun `对象键里的特殊字符逐段编码`() {
        val target = s3RequestTarget("https://s3.example.com", "vault", "备份 目录/tessera.vault", true)
        assertEquals("/vault/%E5%A4%87%E4%BB%BD%20%E7%9B%AE%E5%BD%95/tessera.vault", target.canonicalUri)
        assertEquals("https://s3.example.com/vault/%E5%A4%87%E4%BB%BD%20%E7%9B%AE%E5%BD%95/tessera.vault", target.url)
    }

    @Test
    fun `地址无法解析时给出可读错误`() {
        try {
            s3RequestTarget("这不是地址", "vault", "tessera.vault", false)
            fail("应当抛出 SyncException")
        } catch (e: SyncException) {
            assertTrue(e.message!!.contains("S3 地址"))
        }
    }

    @Test
    fun `对象键由前缀与文件名拼成`() {
        assertEquals("tessera.vault", objectKey("", "tessera.vault"))
        assertEquals("backup/2fa/tessera.vault", objectKey("backup/2fa/", "tessera.vault"))
        assertEquals("backup/tessera.vault", objectKey("  /backup/  ", "tessera.vault"))
        assertEquals("tessera.vault", objectKey("", "  "))
    }

    @Test
    fun `uriEncode 只保留 RFC3986 未保留字符`() {
        assertEquals("a-b_c.d~e", uriEncode("a-b_c.d~e"))
        assertEquals("a%20b", uriEncode("a b"))
        assertEquals("a%2Fb", uriEncode("a/b"))
        assertEquals("%E4%B8%AD", uriEncode("中"))
    }

    // ---------- 对象读写（本地假服务器） ----------

    private fun withServer(
        handler: (HttpExchange) -> Unit,
        block: (String) -> Unit
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            try {
                handler(exchange)
            } finally {
                exchange.close()
            }
        }
        server.executor = Executors.newSingleThreadExecutor()
        server.start()
        try {
            block("http://127.0.0.1:" + server.address.port)
        } finally {
            server.stop(0)
        }
    }

    private fun respond(exchange: HttpExchange, code: Int, body: String = "", headers: Map<String, String> = emptyMap()) {
        val bytes = body.toByteArray()
        headers.forEach { (k, v) -> exchange.responseHeaders.add(k, v) }
        if (bytes.isEmpty()) {
            exchange.sendResponseHeaders(code, -1)
        } else {
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private fun provider(endpoint: String, pathStyle: Boolean = true) = S3Provider(
        endpoint = endpoint,
        bucket = "vault",
        accessKey = "AKIAIOSFODNN7EXAMPLE",
        secretKey = "secret",
        prefix = "backup",
        forcePathStyle = pathStyle
    )

    @Test
    fun `云端还没有备份时 pull 返回 null`() = withServer({ respond(it, 404) }, { endpoint ->
        runBlocking { assertNull(provider(endpoint).pull()) }
    })

    @Test
    fun `pull 带回内容与 ETag`() = withServer({ exchange ->
        respond(exchange, 200, "payload", mapOf("ETag" to "\"abc123\""))
    }, { endpoint ->
        val file = runBlocking { provider(endpoint).pull() }
        assertEquals("payload", file?.content)
        assertEquals("\"abc123\"", file?.etag)
    })

    @Test
    fun `push 发的是与签名一致的请求`() {
        val captured = java.util.concurrent.CompletableFuture<Map<String, String>>()
        withServer({ exchange ->
            val received = exchange.requestBody.readBytes().decodeToString()
            captured.complete(
                mapOf(
                    "body" to received,
                    "method" to exchange.requestMethod,
                    "path" to exchange.requestURI.path,
                    "host" to exchange.requestHeaders.getFirst("Host").orEmpty(),
                    "localHost" to (exchange.localAddress.hostString + ":" + exchange.localAddress.port),
                    "payloadHash" to exchange.requestHeaders.getFirst("x-amz-content-sha256").orEmpty(),
                    "expectedHash" to AwsV4.sha256Hex(received.toByteArray()),
                    "authorization" to exchange.requestHeaders.getFirst("Authorization").orEmpty()
                )
            )
            respond(exchange, 200)
        }, { endpoint ->
            assertTrue(runBlocking { provider(endpoint).push("{\"v\":2}", null) })
        })
        val c = captured.get()
        assertEquals("{\"v\":2}", c["body"])
        assertEquals("PUT", c["method"])
        assertEquals("/vault/backup/tessera.vault", c["path"])
        // Host 头必须与签名时用的 Host 完全一致，否则服务端会判签名不匹配
        assertEquals(c["localHost"], c["host"])
        assertEquals(c["expectedHash"], c["payloadHash"])
        val authorization = c.getValue("authorization")
        assertTrue(authorization.startsWith("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/"))
        assertTrue(authorization.contains("SignedHeaders=host;x-amz-content-sha256;x-amz-date"))
    }

    @Test
    fun `delete 在对象不存在时同样成功`() = withServer({ respond(it, 404) }, { endpoint ->
        assertTrue(runBlocking { provider(endpoint).delete() })
    })

    @Test
    fun `delete 成功时返回 true`() = withServer({ respond(it, 204) }, { endpoint ->
        assertTrue(runBlocking { provider(endpoint).delete() })
    })

    @Test
    fun `403 转成密钥相关的可读错误`() = withServer({ respond(it, 403) }, { endpoint ->
        try {
            runBlocking { provider(endpoint).pull() }
            fail("应当抛出 SyncException")
        } catch (e: SyncException) {
            assertEquals(403, e.code)
            assertTrue(e.message!!.contains("Access Key"))
        }
    })
}
