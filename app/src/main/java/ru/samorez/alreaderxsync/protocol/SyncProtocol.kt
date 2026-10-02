package ru.samorez.alreaderxsync.protocol

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Протокол обмена сообщениями между клиентом и сервером синхронизации.
 *
 * Формат передачи: каждое JSON-сообщение записывается в поток как одна
 * строка в UTF-8, завершающаяся символом '\n'. После [SyncMessage.FileHeader]
 * в тот же поток идут «сырые» байты содержимого файла.
 *
 * ВАЖНО (буферизация): чтение JSON выполняется побайтово из [InputStream]
 * без использования [java.io.BufferedReader]. BufferedReader буферизует
 * данные и «съедает» часть бинарных байтов, которые должны попасть в
 * последующий вызов [InputStream.read].
 *
 * ВАЖНО (UTF-8): байты строки накапливаются в [ByteArrayOutputStream] и
 * декодируются один раз через [String] с [Charsets.UTF_8]. Побайтовое
 * преобразование `b.toChar()` ломает многобайтовые последовательности
 * UTF-8 (кириллица превращается в mojibake).
 */
object SyncProtocol {

    /** Тег для логирования. */
    private const val TAG = "SyncProtocol"

    /** Текущая версия протокола. */
    const val PROTOCOL_VERSION = 1

    /** Настройки JSON-сериализации. */
    private val json = Json {
        ignoreUnknownKeys = true      // не падать на неизвестных полях
        encodeDefaults = true         // включать поля со значениями по умолчанию
        classDiscriminator = "type"   // имя поля-дискриминатора для sealed-класса
    }

    /** Размер буфера при копировании файлов. */
    private const val FILE_BUFFER_SIZE = 8192

    /**
     * Кодирует сообщение в строку JSON с завершающим переводом строки.
     *
     * [Json.encodeToString] возвращает обычную Kotlin-строку (внутри UTF-16).
     * Корректное преобразование в байты UTF-8 происходит позже — в
     * [sendMessage] через [String.toByteArray] с [Charsets.UTF_8].
     */
    fun encode(message: SyncMessage): String =
        json.encodeToString(message) + "\n"

    /**
     * Декодирует сообщение из строки JSON.
     *
     * На вход приходит уже корректно декодированная строка (после
     * [readLineFromStream]), поэтому дополнительных преобразований
     * кодировки здесь не требуется — только обрезка пробелов по краям.
     */
    fun decode(line: String): SyncMessage =
        json.decodeFromString<SyncMessage>(line.trim())

    /**
     * Отправляет сообщение в выходной поток.
     *
     * encode() уже добавил '\n' в конец. Строка кодируется в UTF-8
     * через [String.toByteArray] и пишется напрямую в [OutputStream].
     * Никаких [java.io.BufferedWriter] / [java.io.OutputStreamWriter],
     * чтобы не менять поведение записи и не добавлять лишних символов.
     */
    suspend fun sendMessage(output: OutputStream, message: SyncMessage) {
        Log.d("SyncProtocol", "sendMessage: entering, sleeping 200ms")
        val jsonStr = encode(message)
        withContext(Dispatchers.IO) {
            output.write(jsonStr.toByteArray(Charsets.UTF_8))
            Thread.sleep(200)
            output.flush()
        }
        Log.d("SyncProtocol", "sendMessage: exiting")
    }

    /**
     * Читает одно сообщение из входного потока.
     *
     * После возврата из этой функции позиция в [input] указывает ровно
     * на первый байт, следующий за JSON-строкой (то есть на начало
     * бинарных данных, если они есть).
     */
    suspend fun readMessage(input: InputStream): SyncMessage {
        val line = readLineFromStream(input)
        if (line.isBlank()) {
            throw IOException("Empty line where JSON expected")
        }
        return decode(line)
    }

    /**
     * Отправляет заголовок файла перед передачей его содержимого.
     * Ничего не меняется — просто делегирует в [sendMessage].
     */
    suspend fun sendFileHeader(output: OutputStream, path: String, size: Long) {
        sendMessage(output, SyncMessage.FileHeader(path, size))
    }

    /**
     * Копирует содержимое файла из [input] в [output] через буфер.
     * Возвращает количество переданных байт.
     *
     * Используется блочное чтение: для бинарных данных это и быстро,
     * и корректно. Построчное чтение здесь недопустимо.
     */
    suspend fun sendFileBytes(output: OutputStream, input: InputStream): Long {
        val buffer = ByteArray(FILE_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            output.write(buffer, 0, read)
            total += read
        }
        output.flush()
        return total
    }

    /**
     * Читает одну строку из [InputStream] побайтово до '\n'.
     *
     * Байты накапливаются в [ByteArrayOutputStream] и декодируются один
     * раз через UTF-8. Это критично для кириллицы в путях файлов: побайтовое
     * `b.toChar()` разрывает многобайтовые последовательности UTF-8 и
     * даёт mojibake.
     *
     * Символы '\r' игнорируются (поддержка CRLF). При неожиданном EOF
     * бросается [IOException] с указанием количества уже прочитанных байт.
     *
     * Намеренно НЕ используется [java.io.BufferedReader] и НЕ используется
     * [InputStream.read] с массивом: только одиночные [InputStream.read],
     * чтобы не «съесть» байты, идущие сразу после JSON.
     */
    private suspend fun readLineFromStream(input: InputStream): String =
        withContext(Dispatchers.IO) {
            val buffer = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b == -1) {
                    throw IOException(
                        "Unexpected EOF while reading line " +
                                "(got ${buffer.size()} bytes so far)"
                    )
                }
                if (b == '\n'.code) break
                if (b != '\r'.code) buffer.write(b)
            }
            val bytes = buffer.toByteArray()
            val result = String(bytes, Charsets.UTF_8)
            Log.d(TAG, "readLine: ${bytes.size} bytes, string length=${result.length}")
            result
        }
}