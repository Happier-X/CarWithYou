package com.carwithyou.lite.adb

import android.content.Context
import android.os.Build
import com.carwithyou.adb.AdbKeyPair
import com.carwithyou.lite.AppLog
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.security.KeyFactory
import java.util.Date

/**
 * Android 11+ 无线调试配对管理器。
 *
 * 包装 `libadb-android` 的 [AbsAdbConnectionManager]，仅负责两件事：
 * 1. 用 6 位配对码完成 SPAKE2 + TLS 配对（一次性）
 * 2. 连上手机的无线调试端口，发送 `tcpip:5555`，把经典的 5555 明文端口开出来
 *
 * 关键设计：**复用车机端现有的 ADB RSA 密钥**（`files/adb/adbkey`），
 * 用它自签一张 X.509 证书。这样配对时手机授权的那把密钥，就是后续明文
 * 连接时用到的那把，避免用户在手机上被弹两次确认。
 */
class AdbPairManager(private val context: Context) : AbsAdbConnectionManager() {

    private companion object {
        const val TAG = "AdbPair"
    }

    private val privateKey: PrivateKey
    private val certificate: X509Certificate
    private val deviceName: String

    init {
        setApi(Build.VERSION_CODES.R)
        val keyDir = File(context.filesDir, "adb").apply { mkdirs() }
        val pair = AdbKeyPair.loadOrCreate(keyDir)
        privateKey = pair.privateKey

        val certFile = File(keyDir, "pair_cert.der")
        val cached = if (certFile.exists()) readCert(certFile) else null
        if (cached != null && certMatchesKey(cached, pair.publicKey)) {
            certificate = cached
            // 复用已有证书时，设备名必须与证书 CN 保持一致（证书是手机的授权凭据，名字不一致会让用户对账困惑）
            deviceName = extractCn(cached) ?: "CarWithYou-${stableId(pair.publicKey.encoded)}"
        } else {
            if (cached != null) {
                AppLog.w(TAG, "发现陈旧配对证书（与当前 adbkey 不匹配），重新生成")
            }
            val shortId = stableId(pair.publicKey.encoded)
            deviceName = "CarWithYou-$shortId"
            certificate = generateAndSaveCert(pair.publicKey as RSAPublicKey, certFile)
        }

        // 这两个值要发给用户对账（手机上会显示），记进诊断日志便于事后核对
        AppLog.i(TAG, "配对身份就绪：设备名=$deviceName 指纹=$fingerprint " +
                "密钥文件=${File(keyDir, "adbkey").name}")
    }

    /**
     * 从密钥字节取 4 位十六进制短 id（**内容稳定，跨重启不变**）。
     *
     * 切忌改用 {@code privateKey.hashCode()}：那是对象标识哈希，
     * 每次进程重启重新从 PKCS8 反序列化出来的对象都不同 → 设备名每次都变，
     * 而手机上「已配对设备」里记的是首次那个名字，用户会对不上。
     */
    private fun stableId(keyBytes: ByteArray): String = try {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.digest(keyBytes).take(2).joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        "0000"
    }

    /** 提取证书的主题 CN（如 "CarWithYou-e83b"）。 */
    private fun extractCn(cert: X509Certificate): String? = try {
        val dn = cert.subjectX500Principal.name
        dn.split(",").map { it.trim() }.firstOrNull { it.startsWith("CN=", ignoreCase = true) }?.substring(3)
    } catch (_: Exception) {
        null
    }

    /**
     * 证书公钥是否就是当前这把 adbkey 的公钥（比字节，不比值比较）。
     *
     * 不校验的话，一旦 adbkey 被换过（文件丢失/手动替换），留的旧证书会和
     * 明文连接用的密钥对不上，配对看似成功、后续却要再授权一次。
     */
    private fun certMatchesKey(cert: X509Certificate, publicKey: java.security.PublicKey): Boolean =
        try {
            cert.publicKey.encoded.contentEquals(publicKey.encoded)
        } catch (_: Exception) {
            false
        }

    override fun getPrivateKey(): PrivateKey = privateKey
    override fun getCertificate(): Certificate = certificate
    override fun getDeviceName(): String = deviceName

    /** 设备名，如 `CarWithYou-a1b2`，手机的配对列表里会显示这个名字。 */
    val keyDisplayName: String get() = deviceName

    /** 证书 SHA-256 指纹的前 8 字符，方便用户对账。 */
    val fingerprint: String
        get() = try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            md.digest(certificate.encoded).take(4).joinToString(":") { "%02X".format(it) }
        } catch (_: Exception) {
            "????"
        }

    /**
     * 执行配对：连接手机显示在「使用配对码配对设备」弹窗里的 IP、配对端口和 6 位配对码。
     * 阻塞调用，内部切到 IO 线程。
     */
    suspend fun pairWithCode(host: String, pairingPort: Int, pairingCode: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(host.isNotBlank()) { "IP 不能为空" }
                require(pairingPort in 1..65535) { "配对端口不合法：$pairingPort" }
                require(pairingCode.isNotBlank()) { "配对码不能为空" }
                // 基类同名方法返回 Boolean（true=成功），包一层成 Result 好统一处理
                check(super.pair(host, pairingPort, pairingCode.trim())) { "配对被手机拒绝（配对码错误或超时）" }
            }
        }

    /**
     * 连上手机无线调试的主端口（显示在无线调试页面的那个端口，不是配对端口），
     * 发送 `tcpip:5555` 服务请求，让手机 adbd 打开经典明文 5555 端口。
     */
    suspend fun openTcpip5555(host: String, connectPort: Int): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(host.isNotBlank()) { "IP 不能为空" }
                require(connectPort in 1..65535) { "无线调试端口不合法：$connectPort" }
                try {
                    connect(host, connectPort)
                    // 打开 5555 明文端口
                    openStream("tcpip:5555")
                    Unit // openStream 返回流对象，这里只要副作用
                } finally {
                    runCatching { disconnect() }
                }
            }
        }

    /**
     * 一键完成：先配对，再开 5555 端口。
     */
    suspend fun pairAndOpenPort(
        host: String,
        pairingPort: Int,
        pairingCode: String,
        connectPort: Int,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            pairWithCode(host, pairingPort, pairingCode).getOrThrow()
            openTcpip5555(host, connectPort).getOrThrow()
        }
    }

    private fun generateAndSaveCert(publicKey: RSAPublicKey, certFile: File): X509Certificate {
        val name = X500Name("CN=$deviceName")
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 24L * 3600_000)
        // 有效期 10 年，避免上车频繁重新配对
        val notAfter = Date(now + 10L * 365 * 24 * 3600_000)
        val serial = BigInteger.valueOf(now)

        val builder = JcaX509v3CertificateBuilder(
            name,
            serial,
            notBefore,
            notAfter,
            name,
            publicKey
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(privateKey)
        val certHolder = builder.build(signer)
        val cert = JcaX509CertificateConverter().getCertificate(certHolder)

        FileOutputStream(certFile).use { it.write(cert.encoded) }
        return cert
    }

    private fun readCert(file: File): X509Certificate? = try {
        FileInputStream(file).use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as? X509Certificate
        }
    } catch (_: Exception) {
        null
    }
}
