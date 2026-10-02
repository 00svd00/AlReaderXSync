package ru.samorez.alreaderxsync.data

/**
 * Направление синхронизации.
 *
 * PHONE_TO_READER — копируем данные с телефона на читалку.
 * READER_TO_PHONE — копируем данные с читалки на телефон.
 */
enum class SyncDirection {
    PHONE_TO_READER,
    READER_TO_PHONE
}