package com.carwithyou.adb

import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * ADB 的 RSA 密钥对。
 *
 * 握手流程：adbd 先发 `AUTH(TOKEN)`，我们用私钥签这个 token 回 `AUTH(SIGNATURE)`；
 * 如果 adbd 不认（手机上还没授权过这个公钥），再发 `AUTH(RSA_PUBLIC)`，
 * 手机就会弹「是否允许 USB 调试」，用户勾「一律允许」之后这个公钥进 authorized_keys，
 * 之后同一个密钥对就能免确认重连。
 */
class AdbKeyPair(
    val privateKey: PrivateKey,
    val publicKey: PublicKey,
    /** 手机上授权框显示的来源标识。 */
    val name: String = DEFAULT_NAME,
) {
    /** ADB 用 SHA-1 + RSA PKCS#1 v1.5 签 token。 */
    fun sign(token: ByteArray): ByteArray =
        Signature.getInstance("SHA1withRSA").run {
            initSign(privateKey)
            update(token)
            sign()
        }

    /**
     * ADB 的公钥编码（不是 X.509）。
     *
     * 小端结构：
     *   uint32 wordCount          n 的 32 位字数
     *   uint32 n0inv              -1/n[0] mod 2^32
     *   uint32 n[wordCount]       n，小端字
     *   uint32 rr[wordCount]      2^(64*wordCount) mod n，小端字
     *   uint32 exponent           通常 65537
     * 然后 base64，再拼 `" " + name + NUL`。
     */
    fun adbPublicKeyBytes(): ByteArray {
        val rsa = publicKey as RSAPublicKey
        val n = rsa.modulus
        val e = rsa.publicExponent
        val words = (n.bitLength() + 31) / 32
        val nWords = littleEndianWords(n, words)
        val rrWords = littleEndianWords(BigInteger.ONE.shiftLeft(64 * words).mod(n), words)

        val buf = ByteBuffer.allocate(4 * (2 + words * 2 + 1)).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(words)
        buf.putInt(n0inv(nWords[0]))
        for (w in nWords) buf.putInt(w)
        for (w in rrWords) buf.putInt(w)
        buf.putInt(e.toInt())

        val encoded = Base64.getEncoder().encodeToString(buf.array())
        return "$encoded $name\u0000".toByteArray(Charsets.UTF_8)
    }

    /** 自检：n0inv 必须满足 n0inv * n[0] ≡ -1 (mod 2^32)。 */
    fun verifyEncoding(): Boolean {
        val rsa = publicKey as RSAPublicKey
        val words = (rsa.modulus.bitLength() + 31) / 32
        val n0 = littleEndianWords(rsa.modulus, words)[0]
        val inv = n0inv(n0)
        val product = (inv.toLong() and 0xFFFFFFFFL) * (n0.toLong() and 0xFFFFFFFFL)
        return (product and 0xFFFFFFFFL) == 0xFFFFFFFFL
    }

    fun save(dir: File) {
        dir.mkdirs()
        File(dir, PRIVATE_FILE).writeBytes(privateKey.encoded)
        File(dir, PUBLIC_FILE).writeBytes(publicKey.encoded)
        File(dir, NAME_FILE).writeText(name)
    }

    override fun toString(): String = "AdbKeyPair(name=$name)"

    companion object {
        const val DEFAULT_NAME = "carwithyou@car"

        private const val PRIVATE_FILE = "adbkey"
        private const val PUBLIC_FILE = "adbkey.pub"
        private const val NAME_FILE = "adbkey.name"

        fun generate(name: String = DEFAULT_NAME): AdbKeyPair {
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(2048, SecureRandom())
            val pair = generator.generateKeyPair()
            return AdbKeyPair(pair.private, pair.public, name)
        }

        /** 复用已存的密钥对，没有就新建。同一个密钥对要一直用，否则手机会反复弹授权。 */
        fun loadOrCreate(dir: File, name: String = DEFAULT_NAME): AdbKeyPair {
            val privateFile = File(dir, PRIVATE_FILE)
            if (privateFile.isFile) {
                try {
                    val factory = KeyFactory.getInstance("RSA")
                    val priv = factory.generatePrivate(PKCS8EncodedKeySpec(privateFile.readBytes()))
                    // 公钥能跟着补就跟着补：可能只把私钥（PKCS8 DER）拷进来了（比如做桌面自检/授权导出）。
                    // 从 CRT 私钥里拿 n/e 反推公钥，比依赖 adbkey.pub 文件可靠。
                    var pub: java.security.PublicKey? = null
                    if (priv is java.security.interfaces.RSAPrivateCrtKey) {
                        val crt = priv
                        try {
                            pub = factory.generatePublic(
                                java.security.spec.RSAPublicKeySpec(crt.modulus, crt.publicExponent)
                            )
                        } catch (_: Exception) {
                            pub = null
                        }
                    }
                    pub = pub ?: try {
                        factory.generatePublic(X509EncodedKeySpec(File(dir, PUBLIC_FILE).readBytes()))
                    } catch (_: Exception) {
                        null
                    }
                    if (pub == null) throw RuntimeException("私钥在但没有可用的公钥")
                    val storedName = File(dir, NAME_FILE)
                        .takeIf { it.isFile }?.readText()?.trim().orEmpty().ifBlank { name }
                    return AdbKeyPair(priv, pub, storedName)
                } catch (_: Exception) {
                    // 存坏了就当没有，重新生成一份。
                }
            }
            return generate(name).also { it.save(dir) }
        }
    }
}

private val MASK32 = BigInteger.valueOf(0xFFFFFFFFL)

private fun littleEndianWords(value: BigInteger, words: Int): IntArray =
    IntArray(words) { value.shiftRight(32 * it).and(MASK32).toInt() }

private fun n0inv(n0: Int): Int {
    val n = n0.toLong() and 0xFFFFFFFFL
    var inverse = 1L
    // 牛顿迭代，每轮把正确的位数翻倍：1 → 32 位，5 轮够。
    repeat(5) {
        val product = (n * inverse) and 0xFFFFFFFFL
        inverse = (inverse * (2 - product)) and 0xFFFFFFFFL
    }
    return ((0x100000000L - inverse) and 0xFFFFFFFFL).toInt()
}
