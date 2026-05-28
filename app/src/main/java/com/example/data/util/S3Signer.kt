package com.example.data.util

import android.util.Log
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object S3Signer {
    private const val TAG = "S3Signer"

    fun generatePresignedUrl(
        endpoint: String,
        bucketName: String,
        filePath: String,
        accessKeyId: String,
        secretAccessKey: String,
        region: String = "us-east-1",
        expiresSeconds: Long = 3600
    ): String {
        try {
            // Normalize endpoint and construct base URL
            val cleanEndpoint = endpoint.trim().removeSuffix("/")
            val cleanFilePath = filePath.trim().removePrefix("/")

            // We support both absolute R2 domain and bucket-in-path format.
            // If the endpoint contains cloudflarestorage.com but NOT the bucket name, we can do bucket-in-path or virtual host.
            // Let's use bucket-in-path style for highest reliability:
            // Base URL: https://<endpoint_host>/<bucket>/<file_path>
            // host: <endpoint_host>
            
            val urlObj = java.net.URL(cleanEndpoint)
            val host = urlObj.host
            val portStr = if (urlObj.port != -1 && urlObj.port != urlObj.defaultPort) ":${urlObj.port}" else ""
            val fullHost = host + portStr
            
            // For R2 bucket-in-path: uriPath starts with /<bucket>/<filepath>
            val uriPath = "/$bucketName/$cleanFilePath"
            
            // Timestamps
            val now = Date()
            val formatIso8601 = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val formatYyyyMmDd = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            
            val amzDate = formatIso8601.format(now)
            val dateStamp = formatYyyyMmDd.format(now)
            
            val credentialScope = "$dateStamp/$region/s3/aws4_request"
            
            // Build query parameters (must be sorted alphabetically for canonical query string)
            val queryParams = sortedMapOf(
                "X-Amz-Algorithm" to "AWS4-HMAC-SHA256",
                "X-Amz-Credential" to "$accessKeyId/$credentialScope",
                "X-Amz-Date" to amzDate,
                "X-Amz-Expires" to expiresSeconds.toString(),
                "X-Amz-SignedHeaders" to "host"
            )
            
            val canonicalQueryString = queryParams.map { (key, value) ->
                "${encryptUrlComponent(key)}=${encryptUrlComponent(value)}"
            }.joinToString("&")
            
            // Canonical Headers
            val canonicalHeaders = "host:$fullHost\n"
            val signedHeaders = "host"
            
            // Payload hash for GET/PUT is "UNSIGNED-PAYLOAD" for presigned URLs
            val payloadHash = "UNSIGNED-PAYLOAD"
            
            // Canonical Request
            val canonicalRequest = "PUT\n" +
                    "$uriPath\n" +
                    "$canonicalQueryString\n" +
                    "$canonicalHeaders\n" +
                    "$signedHeaders\n" +
                    payloadHash
            
            val hashedCanonicalRequest = sha256Hex(canonicalRequest)
            
            // String to Sign
            val stringToSign = "AWS4-HMAC-SHA256\n" +
                    "$amzDate\n" +
                    "$credentialScope\n" +
                    hashedCanonicalRequest
                    
            // Derive Signing Key
            val kDate = hmacSha256(("AWS4" + secretAccessKey).toByteArray(), dateStamp)
            val kRegion = hmacSha256(kDate, region)
            val kService = hmacSha256(kRegion, "s3")
            val kSigning = hmacSha256(kService, "aws4_request")
            
            // Calculate Signature
            val signature = bytesToHex(hmacSha256(kSigning, stringToSign))
            
            // Final Presigned URL
            val finalUrl = "$cleanEndpoint$uriPath?$canonicalQueryString&X-Amz-Signature=$signature"
            Log.d(TAG, "Generated R2 Presigned PUT: $finalUrl")
            return finalUrl
        } catch (e: Exception) {
            Log.e(TAG, "Error generating R2 presigned URL, returning dummy: ${e.message}", e)
            // Return a fallback PUT endpoint
            return "$endpoint/$bucketName/$filePath"
        }
    }

    private fun encryptUrlComponent(value: String): String {
        return URLEncoder.encode(value, "UTF-8")
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~")
    }

    private fun hmacSha256(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val secretKeySpec = SecretKeySpec(key, "HmacSHA256")
        mac.init(secretKeySpec)
        return mac.doFinal(data.toByteArray())
    }

    private fun sha256Hex(data: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(data.toByteArray())
        return bytesToHex(hash)
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = CharArray(bytes.size * 2)
        val chars = "0123456789abcdef".toCharArray()
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            hexChars[i * 2] = chars[v ushr 4]
            hexChars[i * 2 + 1] = chars[v and 0x0F]
        }
        return String(hexChars)
    }
}
