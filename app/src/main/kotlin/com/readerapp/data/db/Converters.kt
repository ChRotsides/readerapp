package com.readerapp.data.db

import androidx.room.TypeConverter
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class Converters {
    @TypeConverter
    fun paragraphsToJson(paragraphs: List<String>): String = Json.encodeToString(stringList, paragraphs)

    @TypeConverter
    fun jsonToParagraphs(json: String): List<String> =
        if (json.isBlank()) emptyList() else Json.decodeFromString(stringList, json)

    @TypeConverter
    fun chapterStatusToString(value: ChapterStatus): String = value.name

    @TypeConverter
    fun stringToChapterStatus(value: String): ChapterStatus = ChapterStatus.valueOf(value)

    @TypeConverter
    fun variantToString(value: TextVariant): String = value.name

    @TypeConverter
    fun stringToVariant(value: String): TextVariant = TextVariant.valueOf(value)

    @TypeConverter
    fun adapterKindToString(value: AdapterKind): String = value.name

    @TypeConverter
    fun stringToAdapterKind(value: String): AdapterKind = AdapterKind.valueOf(value)

    @TypeConverter
    fun adapterStatusToString(value: AdapterStatus): String = value.name

    @TypeConverter
    fun stringToAdapterStatus(value: String): AdapterStatus = AdapterStatus.valueOf(value)

    @TypeConverter
    fun autoProcessToString(value: AutoProcessMode): String = value.name

    @TypeConverter
    fun stringToAutoProcess(value: String): AutoProcessMode =
        runCatching { AutoProcessMode.valueOf(value) }.getOrDefault(AutoProcessMode.OFF)

    private companion object {
        val stringList = ListSerializer(String.serializer())
    }
}
