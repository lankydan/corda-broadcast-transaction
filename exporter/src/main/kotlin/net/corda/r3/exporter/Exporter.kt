@file:JvmName("Exporter")

package net.corda.r3.exporter

import com.fasterxml.jackson.databind.ObjectMapper
import net.corda.client.jackson.JacksonSupport
import net.corda.client.rpc.CordaRPCClient
import net.corda.core.crypto.SecureHash
import net.corda.core.internal.exists
import net.corda.core.internal.list
import net.corda.core.internal.toTypedArray
import net.corda.core.messaging.ClientRpcSslOptions
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.WireTransaction
import net.corda.core.utilities.NetworkHostAndPort
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.streams.toList

fun main(args: Array<String>) {

    println("Exporting transactions\n")

    val host = args[0]
    val rpcPort = args[1].toInt()
    val username = args[2]
    val password = args[3]
    val directory = args[4]
    val cordappsDirectory = args[5]
    val txId = args[6]
    val (trustStorePath, trustStorePassword) = if (args.size > 7) {
        require(args.size == 8) { "Both the trustStorePath and trustStorePassword must be included as a pair" }
        args[7] to args[8]
    } else {
        null to null
    }
    println("host = $host")
    println("rpcPort = $rpcPort")
    println("username = $username")
    println("password = $password")
    println("directory = $directory")
    println("cordappsDirectory = $cordappsDirectory")
    println("txId = $txId")
    println("trustStorePath = $trustStorePath")
    println("trustStorePassword = $trustStorePassword")
    println("")

    val sslOptions = if (trustStorePath != null && trustStorePassword != null) {
        ClientRpcSslOptions(trustStorePath = Paths.get(trustStorePath), trustStorePassword = trustStorePassword)
    } else {
        null
    }

    val paths = jarUrlsInDirectory(Paths.get(cordappsDirectory))
    val classloader = URLClassLoader(paths.stream().toTypedArray(), CordaRPCClient::class.java.classLoader)

    // Set up connection
    val rpcAddress = NetworkHostAndPort(host, rpcPort)
    val rpcClient = CordaRPCClient(rpcAddress, sslConfiguration = sslOptions, classLoader = classloader)
    val rpcConnection = rpcClient.start(username, password)
    val proxy = rpcConnection.proxy
    println("Connected to the node via rpc\n")
    val txs: List<SignedTransaction> = proxy.internalFindVerifiedTransaction(SecureHash.create(txId))?.let { listOf(it)} ?: emptyList()
    println("Retrieved ${txs.size} transactions\n")
    val objectMapper: ObjectMapper = JacksonSupport.createDefaultMapper(proxy)
    // Write transaction json to separate folders per transaction
    // Fetch the attachments for these transactions and store in the same folder
    if (txs.isEmpty()) {
        println("No transactions with txId = $txId")
    } else {
        for (tx in txs) {
            // Create the folder for the tansaction
            val folder = File("$directory/transaction-${tx.id}")
            folder.delete()
            folder.mkdirs()
            println("Exporting transaction ${tx.id}")
            println("Created folder $directory/transaction-${tx.id}")
            // Create the transaction json output
            val txJsonFile = File("$directory/transaction-${tx.id}/transaction-${tx.id}.json")
            txJsonFile.delete()
            txJsonFile.createNewFile()
            txJsonFile.outputStream().use {
                objectMapper.writeValue(it, tx)
            }
            println("Created file $directory/transaction-${tx.id}/transaction-${tx.id}.json")
            // Check the transaction is a wire transaction so the attachments can be extracted
            if (tx.coreTransaction !is WireTransaction) {
                println("Transaction: ${tx.id} is not a wire transaction and therefore attachments can't be accessed")
            }
//            // Get all the attachments from the transaction and write them to zip files
//            for (attachmentId in (tx.coreTransaction as WireTransaction).attachments) {
//                val inputStream = proxy.openAttachment(attachmentId)
//                // Filename == attachment id
//                val file = File("$directory/transaction-${tx.id}/attachment-${attachmentId}.zip")
//                file.delete()
//                file.createNewFile()
//                // Write the input stream to the created file
//                file.outputStream().use {
//                    inputStream.copyTo(it)
//                }
//                println("Created zip $directory/transaction-${tx.id}/attachment-${attachmentId}.zip")
//            }
            println("Finished exporting transaction ${tx.id}\n")
        }
    }
    rpcConnection.notifyServerAndClose()
    println("Finished exporting")
}

private fun jarUrlsInDirectory(directory: Path): List<URL> {
    return if (!directory.exists()) {
        emptyList()
    } else {
        directory.list { paths ->
            // `toFile()` can't be used here...
            paths.filter { it.toString().endsWith(".jar") }.map { it.toUri().toURL() }.toList()
        }
    }
}