package com.example.pokecamanager

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Checkbox

data class SetInfo(val id: String, val name: String, val official: Int?)
data class CardRow(val setId: String, val setName: String, val number: Int?, val name: String)

data class CardInstance(
    val id: String,
    val cardKey: String,
    val card: CardRow,
    val tags: List<String> = emptyList(),
    val note: String = ""
)class CardData(
    val setsByOfficial: Map<Int, List<SetInfo>>,
    val cardsBySet: Map<String, List<CardRow>>
)
data class Entry(
    val key: String, val setId: String, val setName: String,
    val number: Int?, val name: String, val count: Int
)
data class CollectionInfo(val id: String, val name: String, val note: String)

class Tracker {
    var lockedKey: String? = null
    var candKey: String? = null
    var candCount = 0
    var candTime = 0L
    var lastSeen = 0L
}

fun keyOf(c: CardRow): String =
    if (c.number != null) "${c.setId}|${c.number}" else "${c.setId}|${c.name}"

fun prefsName(id: String): String = if (id == "main") "counts" else "counts_$id"

fun safeFileName(s: String): String {
    val t = s.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
    return if (t.isEmpty()) "cards" else t
}

class Store(
    private val prefs: SharedPreferences,
    private val nameMap: Map<String, CardRow>
) {
    val counts = mutableStateMapOf<String, Int>()
    val instances = mutableStateListOf<CardInstance>()
    var lastCard by mutableStateOf<CardRow?>(null)
    var currentSetId by mutableStateOf<String?>(null)

    // 直近5件の登録履歴
    val history = mutableStateListOf<CardRow>()
    val historyInstanceIds = mutableStateListOf<String?>()
    init {
        prefs.all.forEach { (k, v) ->
            if (v is Int && v > 0) counts[k] = v
        }

        loadInstances()

        if (!prefs.getBoolean("instances_migrated", false)) {
            migrateInstances()
        }

        loadHistory()
    }
    private fun loadInstances() {
        instances.clear()

        val json = prefs.getString("card_instances", null) ?: return

        try {
            val array = JSONArray(json)

            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)

                val cardObj = obj.getJSONObject("card")

                val card = CardRow(
                    setId = cardObj.getString("setId"),
                    setName = cardObj.getString("setName"),
                    number = if (cardObj.isNull("number")) null
                    else cardObj.getInt("number"),
                    name = cardObj.getString("name")
                )

                val tagsArray = obj.getJSONArray("tags")
                val tags = mutableListOf<String>()

                for (j in 0 until tagsArray.length()) {
                    tags.add(tagsArray.getString(j))
                }

                instances.add(
                    CardInstance(
                        id = obj.getString("id"),
                        cardKey = obj.getString("cardKey"),
                        card = card,
                        tags = tags,
                        note = obj.optString("note", "")
                    )
                )
            }
        } catch (e: Exception) {
            instances.clear()
            android.util.Log.e(
                "PokecaManager",
                "個体データの読み込みに失敗しました",
                e
            )
        }
    }

    private fun saveInstances() {
        val array = JSONArray()

        instances.forEach { instance ->
            val cardObj = JSONObject()
                .put("setId", instance.card.setId)
                .put("setName", instance.card.setName)
                .put("number", instance.card.number ?: JSONObject.NULL)
                .put("name", instance.card.name)

            val tagsArray = JSONArray()
            instance.tags.forEach { tagsArray.put(it) }

            array.put(
                JSONObject()
                    .put("id", instance.id)
                    .put("cardKey", instance.cardKey)
                    .put("card", cardObj)
                    .put("tags", tagsArray)
                    .put("note", instance.note)
            )
        }

        prefs.edit()
            .putString("card_instances", array.toString())
            .apply()
    }
    // 個体データの枚数をcountsに反映する
    private fun updateCount(key: String) {
        val count = instances.count { it.cardKey == key }

        if (count <= 0) {
            counts.remove(key)
            prefs.edit().remove(key).apply()
        } else {
            counts[key] = count
            prefs.edit().putInt(key, count).apply()
        }
    }

    // 個体データを1枚追加する
    private fun addInstance(card: CardRow): CardInstance {
        val key = keyOf(card)

        val instance = CardInstance(
            id = java.util.UUID.randomUUID().toString(),
            cardKey = key,
            card = card
        )

        instances.add(instance)
        saveInstances()
        updateCount(key)

        return instance
    }

    // 指定した個体データを1枚削除する
    fun removeInstance(id: String): CardRow? {
        val instance = instances.firstOrNull { it.id == id } ?: return null

        instances.remove(instance)
        saveInstances()
        updateCount(instance.cardKey)

        // 削除した個体に紐づく登録履歴も削除する
        val historyIndex = historyInstanceIds.indexOf(id)
        if (historyIndex >= 0) {
            history.removeAt(historyIndex)
            historyInstanceIds.removeAt(historyIndex)
            saveHistory()
        }

        return instance.card
    }
    // タグ・メモのない個体を優先して1枚削除する
    fun removeUnannotatedInstance(key: String): Boolean {
        val instance = instances.firstOrNull {
            it.cardKey == key &&
                    it.tags.isEmpty() &&
                    it.note.isBlank()
        } ?: return false

        removeInstance(instance.id)
        return true
    }
    fun updateInstance(
        id: String,
        tags: List<String>,
        note: String
    ) {
        val index = instances.indexOfFirst { it.id == id }
        if (index < 0) return

        instances[index] = instances[index].copy(
            tags = tags,
            note = note
        )

        saveInstances()
    }
    // 全カード個体から指定タグを削除する
    fun removeTagFromAllInstances(tag: String) {
        var changed = false

        for (i in instances.indices) {
            val instance = instances[i]

            if (tag in instance.tags) {
                instances[i] = instance.copy(
                    tags = instance.tags.filterNot { it == tag }
                )
                changed = true
            }
        }

        if (changed) {
            saveInstances()
        }
    }

    // 一覧画面から新しい個体を追加する
    fun addCardInstance(card: CardRow) {
        addInstance(card)
    }
    private fun migrateInstances() {
        var allResolved = true

        counts.forEach { (key, count) ->
            val existingCount = instances.count { it.cardKey == key }

            // すでに個体データがある場合、不足分だけ追加する
            if (existingCount >= count) {
                return@forEach
            }

            val card = nameMap[key]
            if (card == null) {
                allResolved = false
                return@forEach
            }

            repeat(count - existingCount) {
                instances.add(
                    CardInstance(
                        id = java.util.UUID.randomUUID().toString(),
                        cardKey = key,
                        card = card
                    )
                )
            }
        }

        saveInstances()

        // すべてのカード情報を確認できた場合だけ移行済みにする
        if (allResolved) {
            prefs.edit().putBoolean("instances_migrated", true).apply()
        }
    }
    private fun loadHistory() {
        history.clear()
        historyInstanceIds.clear()

        val json = prefs.getString("registration_history", null) ?: return

        try {
            val array = JSONArray(json)

            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)

                history.add(
                    CardRow(
                        setId = obj.getString("setId"),
                        setName = obj.getString("setName"),
                        number = if (obj.isNull("number")) null
                        else obj.getInt("number"),
                        name = obj.getString("name")
                    )
                )

                val instanceId = obj.optString("instanceId", "")
                historyInstanceIds.add(instanceId.ifBlank { null })
            }
        } catch (e: Exception) {
            history.clear()
            historyInstanceIds.clear()
        }
    }

    private fun saveHistory() {
        val array = JSONArray()

        history.take(5).forEachIndexed { index, card ->
            array.put(
                JSONObject()
                    .put("setId", card.setId)
                    .put("setName", card.setName)
                    .put("number", card.number ?: JSONObject.NULL)
                    .put("name", card.name)
                    .put("instanceId", historyInstanceIds.getOrNull(index) ?: JSONObject.NULL)
            )
        }

        prefs.edit()
            .putString("registration_history", array.toString())
            .apply()
    }



    fun register(card: CardRow): Int {
        val k = keyOf(card)

        val instance = addInstance(card)
        lastCard = card

        history.add(0, card)
        historyInstanceIds.add(0, instance.id)

        while (history.size > 5) {
            history.removeAt(history.lastIndex)
            historyInstanceIds.removeAt(historyInstanceIds.lastIndex)
        }

        saveHistory()

        return counts[k] ?: 0
    }

    // 直近の登録を1枚取り消す
    fun undo(): CardRow? {
        val card = history.firstOrNull() ?: lastCard ?: return null
        return undoHistory(0)
    }

    // 指定した履歴の登録を1枚取り消す
    fun undoHistory(index: Int): CardRow? {
        if (index !in history.indices) return null

        val card = history[index]
        val instanceId = historyInstanceIds.getOrNull(index)

        val removed = if (instanceId != null) {
            removeInstance(instanceId)
        } else {
            // 旧バージョンの履歴など、個体IDがない場合の互換処理
            val key = keyOf(card)
            val instance = instances.firstOrNull { it.cardKey == key }
                ?: return null
            removeInstance(instance.id)
        }

        if (removed == null) return null

        // removeInstance側で履歴も削除されている
        lastCard = history.firstOrNull()
        saveHistory()

        return card
    }

    // 指定した履歴のカードを別のカードに差し替える
    fun replaceHistory(index: Int, newCard: CardRow): Int {
        if (index !in history.indices) return 0

        val oldCard = history[index]
        val oldKey = keyOf(oldCard)
        val newKey = keyOf(newCard)

        // 履歴に紐づく個体を特定
        var instanceId = historyInstanceIds.getOrNull(index)
        var instanceIndex = instances.indexOfFirst { it.id == instanceId }

        // 旧履歴などでIDがない場合はカードキーから探す
        if (instanceIndex < 0) {
            instanceIndex = instances.indexOfFirst { it.cardKey == oldKey }
            if (instanceIndex < 0) return 0

            instanceId = instances[instanceIndex].id
            historyInstanceIds[index] = instanceId
        }
        if (instanceIndex < 0) return 0
        // 同じ個体IDのままカード情報を差し替える
        instances[instanceIndex] = instances[instanceIndex].copy(
            cardKey = newKey,
            card = newCard
        )

        saveInstances()

        if (oldKey != newKey) {
            updateCount(oldKey)
            updateCount(newKey)
        }

        history[index] = newCard
        lastCard = history.firstOrNull()
        saveHistory()

        return counts[newKey] ?: 0
    }

    // 従来の直前登録修正機能（互換性維持）
    fun replaceLast(card: CardRow): Int {
        if (history.isNotEmpty()) {
            return replaceHistory(0, card)
        }

        val old = lastCard
        if (old != null) {
            val oldKey = keyOf(old)
            val oldInstance = instances.firstOrNull {
                it.cardKey == oldKey
            }

            if (oldInstance != null) {
                removeInstance(oldInstance.id)
            }
        }

        return register(card)
    }
}

class Library(private val prefs: SharedPreferences) {
    val items = mutableStateListOf<CollectionInfo>()
    var activeId by mutableStateOf("main")

    init {
        val s = prefs.getString("items", null)
        if (s != null) {
            try {
                val arr = JSONArray(s)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    items.add(
                        CollectionInfo(o.getString("id"), o.getString("name"), o.optString("note", ""))
                    )
                }
            } catch (e: Exception) {
                // 読み込めない場合は初期状態にする
            }
        }
        if (items.isEmpty()) items.add(CollectionInfo("main", "メイン", ""))
        val saved = prefs.getString("active", null)
        activeId = items.firstOrNull { it.id == saved }?.id ?: items[0].id
        save()
    }

    private fun save() {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("note", it.note))
        }
        prefs.edit().putString("items", arr.toString()).putString("active", activeId).apply()
    }

    fun select(id: String) {
        activeId = id
        save()
    }

    fun create(name: String, note: String) {
        val id = "c" + System.currentTimeMillis()
        items.add(CollectionInfo(id, name, note))
        activeId = id
        save()
    }

    fun update(id: String, name: String, note: String) {
        val i = items.indexOfFirst { it.id == id }
        if (i >= 0) items[i] = items[i].copy(name = name, note = note)
        save()
    }

    fun delete(context: Context, id: String): Boolean {
        if (items.size <= 1) return false
        items.removeAll { it.id == id }
        if (activeId == id) activeId = items[0].id
        context.getSharedPreferences(prefsName(id), Context.MODE_PRIVATE).edit().clear().apply()
        save()
        return true
    }
}

fun parseCsvLine(line: String): List<String> {
    val out = mutableListOf<String>()
    val sb = StringBuilder()
    var inQ = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            inQ && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
            c == '"' -> inQ = !inQ
            c == ',' && !inQ -> { out.add(sb.toString()); sb.clear() }
            else -> sb.append(c)
        }
        i++
    }
    out.add(sb.toString())
    return out
}

fun readCsv(context: Context, file: String): List<List<String>> =
    context.assets.open(file).bufferedReader(Charsets.UTF_8).useLines { seq ->
        seq.drop(1).filter { it.isNotBlank() }.map { parseCsvLine(it) }.toList()
    }

fun loadCardData(context: Context): CardData {
    val sets = readCsv(context, "sets_info.csv").map {
        SetInfo(it.getOrElse(0) { "" }, it.getOrElse(1) { "" }, it.getOrElse(2) { "" }.toIntOrNull())
    }
    val cards = readCsv(context, "cards_ja_final.csv").map {
        CardRow(
            it.getOrElse(0) { "" }, it.getOrElse(1) { "" },
            it.getOrElse(2) { "" }.toIntOrNull(), it.getOrElse(3) { "" }
        )
    }
    return CardData(
        sets.filter { it.official != null }.groupBy { it.official!! },
        cards.groupBy { it.setId }
    )
}

fun findCandidates(data: CardData, number: Int, denom: Int): List<CardRow> {
    val sets = data.setsByOfficial[denom] ?: return emptyList()
    return sets.flatMap { s -> data.cardsBySet[s.id].orEmpty().filter { it.number == number } }
}

fun searchCards(
    data: CardData?, all: List<CardRow>, nameQ: String, numQ: String, denomQ: String,
    setQ: String = ""
): List<CardRow> {
    if (data == null) return emptyList()
    val name = nameQ.trim()
    val set = setQ.trim()
    val num = numQ.trim().toIntOrNull()
    val den = denomQ.trim().toIntOrNull()
    if (name.isEmpty() && set.isEmpty() && num == null && den == null) return emptyList()
    val setIds: Set<String>? = den?.let { d ->
        data.setsByOfficial[d]?.map { it.id }?.toSet() ?: emptySet()
    }
    return all.asSequence()
        .filter { name.isEmpty() || it.name.contains(name, ignoreCase = true) }
        .filter {
            set.isEmpty() || it.setId.contains(set, ignoreCase = true) ||
                    it.setName.contains(set, ignoreCase = true)
        }
        .filter { num == null || it.number == num }
        .filter { setIds == null || it.setId in setIds }
        .take(30)
        .toList()
}

fun toEntry(key: String, count: Int, nameMap: Map<String, CardRow>): Entry {
    val c = nameMap[key]
    if (c != null) return Entry(key, c.setId, c.setName, c.number, c.name, count)
    val setId = key.substringBefore('|')
    val rest = key.substringAfter('|')
    return if (setId == "手入力") Entry(key, setId, "手入力", null, rest, count)
    else Entry(key, setId, "", rest.toIntOrNull(), "(データにないカード)", count)
}

fun q(s: String): String = "\"" + s.replace("\"", "\"\"") + "\""

fun buildCsv(entries: List<Entry>, info: CollectionInfo): String {
    val sb = StringBuilder("set_id,number,card_name,count,list_name,note\n")
    entries.forEach { e ->
        sb.append("${e.setId},${e.number ?: ""},${q(e.name)},${e.count},${q(info.name)},${q(info.note)}\n")
    }
    return sb.toString()
}

fun saveCsvToDownloads(context: Context, fileName: String, content: String): Boolean {
    return try {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "$fileName.csv")
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return false
        resolver.openOutputStream(uri)?.use { out ->
            out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
            out.write(content.toByteArray(Charsets.UTF_8))
        } ?: return false
        true
    } catch (e: Exception) {
        false
    }
}

fun vibrate(context: Context) {
    try {
        @Suppress("DEPRECATION")
        val v = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        v.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
    } catch (e: Exception) {
        // 振動できなくても登録は続ける
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                AppRoot(this)
            }
        }
    }
}

@Composable
fun TabButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val pad = PaddingValues(horizontal = 2.dp)
    if (selected) {
        Button(onClick = onClick, modifier = modifier, contentPadding = pad) {
            Text(label, maxLines = 1)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier, contentPadding = pad) {
            Text(label, maxLines = 1)
        }
    }
}

@Composable
fun AppRoot(owner: LifecycleOwner) {
    val context = LocalContext.current
    // アプリ共通設定
    val settingsPrefs = remember {
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    }

// 読み取り間隔（初期値：30ミリ秒）
    var scanIntervalMs by remember {
        mutableStateOf(settingsPrefs.getInt("scan_interval_ms", 30))
    }

// タグ一覧（並び順を保持して保存）
    val initialTagOptions = remember {
        val savedJson = settingsPrefs.getString("tag_names_json", null)

        if (savedJson != null) {
            runCatching {
                val array = JSONArray(savedJson)
                List(array.length()) { index ->
                    array.getString(index)
                }
            }.getOrDefault(DEFAULT_TAGS)
        } else {
            // 以前の保存形式から移行
            settingsPrefs.getStringSet("tag_names", null)?.toList()
                ?: DEFAULT_TAGS
        }
    }

    var tagOptions by remember {
        mutableStateOf(initialTagOptions)
    }
    // 読み取り間隔を変更・保存
    fun updateScanInterval(value: Int) {
        scanIntervalMs = value
        settingsPrefs.edit()
            .putInt("scan_interval_ms", value)
            .apply()
    }

    // タグ一覧を変更・保存（並び順も保持）
    fun updateTagOptions(value: List<String>) {
        tagOptions = value

        settingsPrefs.edit()
            .putString("tag_names_json", JSONArray(value).toString())
            .remove("tag_names")
            .apply()
    }
    val dataResult = remember { runCatching { loadCardData(context) } }
    val data = dataResult.getOrNull()
    val lib = remember {
        Library(context.getSharedPreferences("collections", Context.MODE_PRIVATE))
    }

    val activeInfo = lib.items.firstOrNull { it.id == lib.activeId } ?: lib.items[0]

    val nameMap = remember(data) {
        data?.cardsBySet?.values?.flatten()?.associateBy { keyOf(it) } ?: emptyMap()
    }
    val store = remember(lib.activeId, nameMap) {
        Store(
            context.getSharedPreferences(prefsName(lib.activeId), Context.MODE_PRIVATE),
            nameMap
        )
    }

    // タグの表示順を変更
    fun moveTag(from: Int, to: Int) {
        if (from !in tagOptions.indices || to !in tagOptions.indices) {
            return
        }

        val reordered = tagOptions.toMutableList()
        val movedTag = reordered.removeAt(from)
        reordered.add(to, movedTag)

        updateTagOptions(reordered)
    }

    // 全コレクションからタグを削除
    fun deleteTagFromAllCollections(tag: String) {
        lib.items.toList().forEach { collection ->

            if (collection.id == lib.activeId) {
                // 現在開いているコレクション
                store.removeTagFromAllInstances(tag)
            } else {
                // それ以外のコレクション
                val otherStore = Store(
                    context.getSharedPreferences(
                        prefsName(collection.id),
                        Context.MODE_PRIVATE
                    ),
                    nameMap
                )

                otherStore.removeTagFromAllInstances(tag)
            }
        }

        // 設定に登録されているタグ一覧からも削除
        updateTagOptions(tagOptions.filterNot { it == tag })
    }
    var tab by remember { mutableStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> ScanScreen(
                    owner,
                    data,
                    store,
                    activeInfo.name,
                    dataResult.exceptionOrNull()?.message,
                    scanIntervalMs
                )
                1 -> ListScreen(store, nameMap, activeInfo, tagOptions)
                2 -> ManualScreen(data, store, activeInfo.name)
                3 -> ListsScreen(lib, store)
                4 -> SettingsScreen(
                    scanIntervalMs = scanIntervalMs,
                    onScanIntervalSave = { updateScanInterval(it) },
                    tagOptions = tagOptions,
                    onAddTag = { tag ->
                        updateTagOptions(tagOptions + tag)
                    },
                    onDeleteTag = { tag ->
                        deleteTagFromAllCollections(tag)
                    },
                    onMoveTag = { from, to ->
                        moveTag(from, to)
                    }
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp)) {
            TabButton("読み取り", tab == 0, Modifier.weight(1f)) { tab = 0 }
            Spacer(Modifier.width(4.dp))
            TabButton("一覧", tab == 1, Modifier.weight(1f)) { tab = 1 }
            Spacer(Modifier.width(4.dp))
            TabButton("手入力", tab == 2, Modifier.weight(1f)) { tab = 2 }
            Spacer(Modifier.width(4.dp))
            TabButton("リスト", tab == 3, Modifier.weight(1f)) { tab = 3 }
            Spacer(Modifier.width(4.dp))
            TabButton("設定", tab == 4, Modifier.weight(1f)) { tab = 4 }
        }
    }
}

@Composable
@androidx.annotation.OptIn(ExperimentalGetImage::class)
fun ScanScreen(
    owner: LifecycleOwner,
    data: CardData?,
    store: Store,
    listName: String,
    loadError: String?,
    scanIntervalMs: Int
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }
    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(Manifest.permission.CAMERA)
    }

    val tracker = remember { Tracker() }
    var readText by remember { mutableStateOf("") }
    var ocrMs by remember { mutableStateOf(0L) }
    val lastTime = remember { AtomicLong(0L) }
    val candidates = remember { mutableStateListOf<CardRow>() }
    val providerRef = remember { arrayOfNulls<ProcessCameraProvider>(1) }
    var fixing by remember { mutableStateOf(false) }
    var historyActionIndex by remember { mutableStateOf<Int?>(null) }
    var historyReplaceIndex by remember { mutableStateOf<Int?>(null) }
    val cameraBusy = remember { AtomicBoolean(false) }
    LaunchedEffect(fixing, historyActionIndex, historyReplaceIndex) {
        val busy = fixing || historyActionIndex != null ||
                historyReplaceIndex != null

        cameraBusy.set(busy)

        if (!busy) {
            tracker.lastSeen = System.currentTimeMillis()
            tracker.candKey = null
            tracker.candCount = 0
            tracker.lockedKey = null
        }
    }
    var status by remember {
        mutableStateOf(
            if (data == null) "データの読み込みに失敗しました: $loadError"
            else "カードの番号をカメラに映してください"
        )
    }

    DisposableEffect(Unit) {
        onDispose { providerRef[0]?.unbindAll() }
    }

    fun doRegister(card: CardRow) {
        val n = store.register(card)
        status = "登録しました\n${card.name}\n${card.setId} #${card.number}   ×$n\n読み取り: $readText"
        candidates.clear()
        vibrate(context)
    }
    // 履歴をタップしたときの操作メニュー
    historyActionIndex?.let { index ->
        val card = store.history.getOrNull(index)

        if (card != null) {
            AlertDialog(
                onDismissRequest = {
                    historyActionIndex = null
                },
                title = { Text("登録履歴の操作") },
                text = {
                    Column {
                        Text(card.name)
                        Text(
                            "${card.setName}  #${card.number ?: "-"}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        historyReplaceIndex = index
                        historyActionIndex = null
                    }) {
                        Text("修正")
                    }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = {
                            val removed = store.undoHistory(index)
                            status = if (removed != null) {
                                "1枚取り消しました\n${removed.name}"
                            } else {
                                "取り消せませんでした"
                            }
                            historyActionIndex = null
                        }) {
                            Text("1枚取消")
                        }
                        TextButton(onClick = {
                            historyActionIndex = null
                        }) {
                            Text("閉じる")
                        }
                    }
                }
            )
        } else {
            historyActionIndex = null
        }
    }

    // 履歴から選んだカードの修正
    historyReplaceIndex?.let { index ->
        ReplaceDialog(
            data = data,
            onPick = { newCard ->
                val n = store.replaceHistory(index, newCard)
                status = if (n > 0) {
                    "修正しました\n${newCard.name}\n${newCard.setId} #${newCard.number} ×$n"
                } else {
                    "修正できませんでした"
                }
                historyReplaceIndex = null
                candidates.clear()
            },
            onCancel = {
                historyReplaceIndex = null
            }
        )
    }
    if (fixing) {
        ReplaceDialog(
            data = data,
            onPick = { card ->
                val n = store.replaceLast(card)
                status = "修正しました\n${card.name}\n${card.setId} #${card.number}   ×$n"
                candidates.clear()
                fixing = false
            },
            onCancel = { fixing = false }
        )
    }

    Column(Modifier.fillMaxSize()) {
        if (granted) {
            AndroidView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .heightIn(min = 180.dp)
                    .padding(bottom = 8.dp),
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    val providerFuture = ProcessCameraProvider.getInstance(ctx)
                    providerFuture.addListener({
                        val provider = providerFuture.get()
                        providerRef[0] = provider
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        val recognizer =
                            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        analysis.setAnalyzer(Executors.newSingleThreadExecutor()) { proxy ->
                            if (cameraBusy.get()) {
                                tracker.lastSeen = System.currentTimeMillis()
                                tracker.candKey = null
                                tracker.candCount = 0
                                proxy.close()
                                return@setAnalyzer
                            }

                            val now = System.currentTimeMillis()
                            if (now - lastTime.get() < scanIntervalMs) {
                                proxy.close()
                                return@setAnalyzer
                            }
                            lastTime.set(now)
                            val media = proxy.image
                            if (media == null) {
                                proxy.close()
                                return@setAnalyzer
                            }
                            val img = InputImage.fromMediaImage(
                                media, proxy.imageInfo.rotationDegrees
                            )
                            val t0 = System.currentTimeMillis()
                            recognizer.process(img)
                                .addOnSuccessListener { text ->
                                    ocrMs = System.currentTimeMillis() - t0

                                    // OCR処理中に操作画面が開いた場合も登録しない
                                    if (cameraBusy.get()) {
                                        tracker.lastSeen = System.currentTimeMillis()
                                        tracker.candKey = null
                                        tracker.candCount = 0
                                        return@addOnSuccessListener
                                    }
                                    if (data == null) return@addOnSuccessListener
                                    val now2 = System.currentTimeMillis()
                                    if (now2 - tracker.lastSeen > 700) {
                                        tracker.lockedKey = null
                                        tracker.candKey = null
                                        tracker.candCount = 0
                                    }
                                    val all = Regex("(\\d{1,3})\\s*/\\s*(\\d{1,3})")
                                        .findAll(text.text).toList()
                                    var hn = 0
                                    var hd = 0
                                    var hc: List<CardRow> = emptyList()
                                    var hr = ""
                                    for (mm in all) {
                                        val n0 = mm.groupValues[1].toInt()
                                        val d0 = mm.groupValues[2].toInt()
                                        val cs = findCandidates(data, n0, d0)
                                        if (cs.isNotEmpty()) {
                                            hn = n0; hd = d0; hc = cs; hr = mm.value
                                            break
                                        }
                                    }
                                    if (hc.isEmpty()) {
                                        if (all.isNotEmpty() && now2 - tracker.lastSeen > 700) {
                                            candidates.clear()
                                            status = "候補なし: ${all[0].value}\n(手入力タブで登録できます)"
                                        }
                                        return@addOnSuccessListener
                                    }
                                    val key = "$hn/$hd"
                                    tracker.lastSeen = now2
                                    if (key == tracker.candKey && now2 - tracker.candTime < 1200) {
                                        tracker.candCount++
                                    } else {
                                        tracker.candCount = 1
                                    }
                                    tracker.candKey = key
                                    tracker.candTime = now2
                                    if (tracker.candCount >= 2 && key != tracker.lockedKey) {
                                        tracker.lockedKey = key
                                        readText = hr
                                        if (hc.size == 1) {
                                            doRegister(hc[0])
                                        } else {
                                            val pref = hc.filter { it.setId == store.currentSetId }
                                            if (pref.size == 1) {
                                                doRegister(pref[0])
                                            } else {
                                                status = "候補が${hc.size}件あります\n読み取り: $hr\n下のボタンから選んでください"
                                                candidates.clear()
                                                candidates.addAll(hc.take(8))
                                            }
                                        }
                                    }
                                }
                                .addOnCompleteListener { proxy.close() }
                        }
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                        )
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                }
            )
        } else {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Text("カメラの許可が必要です", Modifier.padding(16.dp))
            }
        }

        Text(
            "登録先リスト: $listName ",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier
                .padding(start = 8.dp, top = 14.dp, end = 8.dp)
                .offset(y = 2.dp)
        )
        Text(
            "合計 ${store.counts.values.sum()}枚   セット: ${store.currentSetId ?: "未指定"}   OCR ${ocrMs}ms",
            modifier = Modifier.padding(start = 8.dp, end = 8.dp)
        )
        Text(
            status,
            style = MaterialTheme.typography.titleMedium,
            minLines = 3,
            maxLines = 3,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .padding(horizontal = 8.dp)
        )


        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 110.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp)
        ) {
            // 読み取り候補を先に表示
            Text(
                "読み取り候補",
                style = MaterialTheme.typography.titleSmall
            )

            if (candidates.isEmpty()) {
                Text("読み取り候補はありません")
            }

            candidates.forEach { c ->
                Button(
                    onClick = {
                        store.currentSetId = c.setId
                        doRegister(c)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("${c.setId} ${c.setName} #${c.number} ${c.name}")
                }
            }

            // 直近の登録履歴を下に表示
            Text(
                "直近の登録履歴",
                style = MaterialTheme.typography.titleSmall
            )

            if (store.history.isEmpty()) {
                Text("まだ登録履歴はありません")
            }

            store.history.forEachIndexed { index, card ->
                OutlinedButton(
                    onClick = { historyActionIndex = index },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("${card.name}  ${card.setId} #${card.number ?: "-"}")
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp)) {
            OutlinedButton(onClick = {
                store.lastCard?.let { doRegister(it) }
            }) { Text("もう1枚") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                val c = store.undo()
                status = if (c != null) "取り消しました\n${c.name}" else "取り消せるカードがありません"
            }) { Text("取消") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { fixing = true }) { Text("修正") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { store.currentSetId = null }) { Text("セット解除") }
        }
    }
}

@Composable
fun ListScreen(
    store: Store,
    nameMap: Map<String, CardRow>,
    info: CollectionInfo,
    tagOptions: List<String>
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    // タグ絞り込み用
    var selectedFilterTags by remember(store) {
        mutableStateOf(setOf<String>())
    }

// AND / OR の切り替え
    var tagMatchMode by remember(store) {
        mutableStateOf("AND")
    }
    var confirmKey by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("") }
    var decrementKey by remember { mutableStateOf<String?>(null) }
    var editKey by remember { mutableStateOf<String?>(null) }
    var editingInstance by remember { mutableStateOf<CardInstance?>(null) }
    val entries = store.counts.entries
        .map { (k, v) -> toEntry(k, v, nameMap) }
        .sortedWith(compareBy({ it.setId }, { it.number ?: Int.MAX_VALUE }, { it.name }))
    // カード個体をカード番号ごとにまとめる
    val instancesByCard = store.instances.groupBy {
        it.cardKey
    }

// カード名検索とタグ検索を組み合わせる
    val filtered = entries.filter { entry ->

        // 既存の文字検索
        val matchesQuery = query.isBlank() ||
                "${entry.setId} ${entry.setName} ${entry.number ?: ""} ${entry.name}"
                    .contains(query.trim(), ignoreCase = true)

        // タグ検索
        val matchesTags = if (selectedFilterTags.isEmpty()) {
            true
        } else {
            val cardInstances = instancesByCard[entry.key].orEmpty()

            cardInstances.any { instance ->
                when (tagMatchMode) {
                    "AND" -> selectedFilterTags.all {
                        it in instance.tags
                    }

                    else -> selectedFilterTags.any {
                        it in instance.tags
                    }
                }
            }
        }

        matchesQuery && matchesTags
    }

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Text("リスト: ${info.name}", style = MaterialTheme.typography.titleMedium)
        if (info.note.isNotBlank()) Text("備考: ${info.note}")
        Text("登録: ${entries.size}種類 / 合計${entries.sumOf { it.count }}枚")
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("検索(カード名・セット・番号)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))

        Text(
            text = "タグで絞り込み",
            style = MaterialTheme.typography.titleSmall
        )

        if (tagOptions.isNotEmpty()) {

            // AND / OR 切り替え
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { tagMatchMode = "AND" }
                ) {
                    Text(
                        if (tagMatchMode == "AND") {
                            "✓ AND（すべて）"
                        } else {
                            "AND（すべて）"
                        }
                    )
                }

                Spacer(Modifier.width(8.dp))

                OutlinedButton(
                    onClick = { tagMatchMode = "OR" }
                ) {
                    Text(
                        if (tagMatchMode == "OR") {
                            "✓ OR（いずれか）"
                        } else {
                            "OR（いずれか）"
                        }
                    )
                }
            }

            // タグ選択一覧
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 140.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                tagOptions.forEach { tag ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = tag in selectedFilterTags,
                            onCheckedChange = { checked ->
                                selectedFilterTags =
                                    if (checked) {
                                        selectedFilterTags + tag
                                    } else {
                                        selectedFilterTags - tag
                                    }
                            }
                        )

                        Text(tag)
                    }
                }
            }

            // 選択解除
            if (selectedFilterTags.isNotEmpty()) {
                TextButton(
                    onClick = {
                        selectedFilterTags = emptySet()
                    }
                ) {
                    Text("タグ選択を全解除（${selectedFilterTags.size}件選択中）")
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {
                val ok = saveCsvToDownloads(
                    context, safeFileName(info.name), buildCsv(entries, info)
                )
                message = if (ok) "Downloadフォルダに保存しました: ${safeFileName(info.name)}.csv"
                else "保存に失敗しました"
            }) { Text("CSV保存") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("cards", buildCsv(entries, info)))
                message = "CSVをコピーしました(${entries.size}種類)"
            }) { Text("CSVをコピー") }
        }
        if (message.isNotEmpty()) Text(message)
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(filtered, key = { it.key }) { e ->
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text("${e.setId} #${e.number ?: "-"}  ${e.name}")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "×${e.count}",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.width(64.dp)
                        )
                        OutlinedButton(
                            onClick = {
                                if (e.count > 1) {
                                    val removed = store.removeUnannotatedInstance(e.key)

                                    if (!removed) {
                                        decrementKey = e.key
                                    }
                                }
                            }
                        ) {
                            Text("−")
                        }

                        Spacer(Modifier.width(8.dp))

                        OutlinedButton(
                            onClick = {
                                val card = nameMap[e.key]
                                if (card != null) {
                                    store.addCardInstance(card)
                                } else {
                                    message = "カード情報を取得できませんでした"
                                }
                            }
                        ) {
                            Text("＋")
                        }

                        Spacer(Modifier.width(8.dp))

                        OutlinedButton(
                            onClick = {
                                confirmKey = e.key
                            }
                        ) {
                            Text("削除")
                        }
                        Spacer(Modifier.width(8.dp))

                        OutlinedButton(
                            onClick = {
                                editKey = e.key
                            }
                        ) {
                            Text("個体編集")
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }

    confirmKey?.let { k ->
        val targets = store.instances.filter { it.cardKey == k }

        AlertDialog(
            onDismissRequest = { confirmKey = null },
            title = {
                Text("削除する個体を選択")
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 350.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(nameMap[k]?.name ?: k)

                    if (targets.isEmpty()) {
                        Text("削除できる個体データがありません")
                    }

                    targets.forEachIndexed { index, instance ->
                        OutlinedButton(
                            onClick = {
                                store.removeInstance(instance.id)
                                confirmKey = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                Text("個体 ${index + 1} を削除")
                                Text(
                                    if (instance.tags.isEmpty()) {
                                        "タグなし"
                                    } else {
                                        "タグ: ${instance.tags.joinToString(" / ")}"
                                    }
                                )
                                Text(
                                    if (instance.note.isBlank()) {
                                        "メモなし"
                                    } else {
                                        "メモ: ${instance.note}"
                                    }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    onClick = { confirmKey = null }
                ) {
                    Text("閉じる")
                }
            }
        )
    }
        decrementKey?.let { k ->
            val targets = store.instances.filter { it.cardKey == k }

            AlertDialog(
                onDismissRequest = { decrementKey = null },
                title = {
                    Text("1枚減らす個体を選択")
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 350.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(nameMap[k]?.name ?: k)

                        targets.forEachIndexed { index, instance ->
                            OutlinedButton(
                                onClick = {
                                    store.removeInstance(instance.id)
                                    decrementKey = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column {
                                    Text("個体 ${index + 1}")
                                    Text(
                                        if (instance.tags.isEmpty()) {
                                            "タグなし"
                                        } else {
                                            "タグ: ${instance.tags.joinToString(" / ")}"
                                        }
                                    )
                                    Text(
                                        if (instance.note.isBlank()) {
                                            "メモなし"
                                        } else {
                                            "メモ: ${instance.note}"
                                        }
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(
                        onClick = { decrementKey = null }
                    ) {
                        Text("閉じる")
                    }
                }
            )
        }
    editKey?.let { k ->
        val targets = store.instances.filter { it.cardKey == k }

        AlertDialog(
            onDismissRequest = { editKey = null },
            title = {
                Text("編集する個体を選択")
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 350.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    targets.forEachIndexed { index, instance ->
                        OutlinedButton(
                            onClick = {
                                editingInstance = instance
                                editKey = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                Text("個体 ${index + 1}")
                                Text(
                                    if (instance.tags.isEmpty()) {
                                        "タグなし"
                                    } else {
                                        "タグ: ${instance.tags.joinToString(" / ")}"
                                    }
                                )
                                Text(
                                    if (instance.note.isBlank()) {
                                        "メモなし"
                                    } else {
                                        "メモ: ${instance.note}"
                                    }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { editKey = null }) {
                    Text("閉じる")
                }
            }
        )
    }

    editingInstance?.let { instance ->
        InstanceEditDialog(
            instance = instance,
            tagOptions = tagOptions,
            onSave = { tags, note ->
                store.updateInstance(instance.id, tags, note)
                editingInstance = null
                message = "個体情報を保存しました"
            },
            onCancel = {
                editingInstance = null
            }
        )
    }
}
@Composable
fun InstanceEditDialog(
    instance: CardInstance,
    tagOptions: List<String>,
    onSave: (List<String>, String) -> Unit,
    onCancel: () -> Unit
) {
    var selectedTags by remember(instance.id) {
        mutableStateOf(instance.tags.toSet())
    }

    var note by remember(instance.id) {
        mutableStateOf(instance.note)
    }

    // 設定画面の並び順を優先し、既存の未登録タグも表示する
    val displayTags = tagOptions + selectedTags
        .filterNot { it in tagOptions }
        .sorted()

    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text("個体情報の編集")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 450.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text("タグ")

                displayTags.forEach { tag ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = tag in selectedTags,
                            onCheckedChange = { checked ->
                                selectedTags = if (checked) {
                                    selectedTags + tag
                                } else {
                                    selectedTags - tag
                                }
                            }
                        )

                        Text(tag)
                    }
                }

                Spacer(Modifier.height(12.dp))

                Text("メモ")

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = {
                        Text("個体ごとのメモ")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(selectedTags.toList(), note)
                }
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("キャンセル")
            }
        }
    )
}
@Composable
fun ManualScreen(data: CardData?, store: Store, listName: String) {
    var nameQ by remember { mutableStateOf("") }
    var numQ by remember { mutableStateOf("") }
    var denomQ by remember { mutableStateOf("") }
    var customName by remember { mutableStateOf("") }
    var message by remember {
        mutableStateOf("カード名・番号・分母のどれかを入力して、出てきたカードをタップすると登録できます")
    }
    val allCards = remember(data) { data?.cardsBySet?.values?.flatten() ?: emptyList() }
    val results = remember(nameQ, numQ, denomQ, data) {
        searchCards(data, allCards, nameQ, numQ, denomQ)
    }
    val numberKeyboard = KeyboardOptions(keyboardType = KeyboardType.Number)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)
    ) {
        Text("登録先リスト: $listName", style = MaterialTheme.typography.titleSmall)
        Text(message, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = nameQ,
            onValueChange = { nameQ = it },
            label = { Text("カード名(一部でも可)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = numQ,
                onValueChange = { numQ = it },
                label = { Text("番号") },
                singleLine = true,
                keyboardOptions = numberKeyboard,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = denomQ,
                onValueChange = { denomQ = it },
                label = { Text("分母(例: 165)") },
                singleLine = true,
                keyboardOptions = numberKeyboard,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(8.dp))
        if (results.isEmpty() && (nameQ.isNotBlank() || numQ.isNotBlank() || denomQ.isNotBlank())) {
            Text("該当するカードがありません")
        }
        results.forEach { c ->
            Button(
                onClick = {
                    val n = store.register(c)
                    message = "登録しました: ${c.name} (${c.setId} #${c.number})  ×$n"
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("${c.setId} ${c.setName} #${c.number} ${c.name}") }
        }
        if (results.size >= 30) {
            Text("30件まで表示しています。条件を足して絞ってください")
        }
        Spacer(Modifier.height(24.dp))
        Text("データにないカードを追加", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = customName,
            onValueChange = { customName = it },
            label = { Text("カード名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                val name = customName.trim()
                if (name.isNotEmpty()) {
                    val n = store.register(CardRow("手入力", "手入力", null, name))
                    message = "登録しました: $name  ×$n"
                    customName = ""
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("この名前で追加") }
    }
}

@Composable
fun InfoDialog(
    title: String, initName: String, initNote: String,
    onOk: (String, String) -> Unit, onCancel: () -> Unit
) {
    var name by remember { mutableStateOf(initName) }
    var note by remember { mutableStateOf(initNote) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("ファイル名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("備考") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank()) onOk(name.trim(), note.trim())
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("やめる") } }
    )
}

@Composable
fun ListsScreen(lib: Library, store: Store) {
    val context = LocalContext.current
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CollectionInfo?>(null) }
    var deleting by remember { mutableStateOf<CollectionInfo?>(null) }

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Text("リスト(CSVごとの登録先)", style = MaterialTheme.typography.titleMedium)
        Text("「使う」を押したリストに、読み取りや手入力のカードが登録されます")
        Spacer(Modifier.height(8.dp))
        Button(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) {
            Text("新しいリストを作る")
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(lib.items.toList(), key = { it.id }) { info ->
                val active = info.id == lib.activeId
                val summary = if (active) {
                    "${store.counts.size}種類 / ${store.counts.values.sum()}枚"
                } else {
                    val all = context.getSharedPreferences(prefsName(info.id), Context.MODE_PRIVATE).all
                    "${all.size}種類 / ${all.values.sumOf { (it as? Int) ?: 0 }}枚"
                }
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(
                        info.name + if (active) "  【使用中】" else "",
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (info.note.isNotBlank()) Text("備考: ${info.note}")
                    Text(summary)
                    Row {
                        if (!active) {
                            OutlinedButton(onClick = { lib.select(info.id) }) { Text("使う") }
                            Spacer(Modifier.width(8.dp))
                        }
                        OutlinedButton(onClick = { editing = info }) { Text("編集") }
                        if (lib.items.size > 1) {
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = { deleting = info }) { Text("削除") }
                        }
                    }
                }
            }
        }
    }

    if (creating) {
        InfoDialog(
            title = "新しいリスト",
            initName = "",
            initNote = "",
            onOk = { n, note ->
                lib.create(n, note)
                creating = false
            },
            onCancel = { creating = false }
        )
    }
    editing?.let { info ->
        InfoDialog(
            title = "リストの編集",
            initName = info.name,
            initNote = info.note,
            onOk = { n, note ->
                lib.update(info.id, n, note)
                editing = null
            },
            onCancel = { editing = null }
        )
    }
    deleting?.let { info ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("リストを削除しますか?") },
            text = { Text("「${info.name}」に登録した内容も、すべて消えます。") },
            confirmButton = {
                TextButton(onClick = {
                    lib.delete(context, info.id)
                    deleting = null
                }) { Text("削除する") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("やめる") }
            }
        )
    }
}
@Composable
fun ReplaceDialog(data: CardData?, onPick: (CardRow) -> Unit, onCancel: () -> Unit) {
    var nameQ by remember { mutableStateOf("") }
    var setQ by remember { mutableStateOf("") }
    var numQ by remember { mutableStateOf("") }
    var denomQ by remember { mutableStateOf("") }
    val allCards = remember(data) { data?.cardsBySet?.values?.flatten() ?: emptyList() }
    val results = remember(nameQ, setQ, numQ, denomQ, data) {
        searchCards(data, allCards, nameQ, numQ, denomQ, setQ)
    }
    val numberKeyboard = KeyboardOptions(keyboardType = KeyboardType.Number)

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("登録の修正") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text("対象のカードを、選んだカードに差し替えます")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = nameQ,
                    onValueChange = { nameQ = it },
                    label = { Text("カード名(一部でも可)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = setQ,
                    onValueChange = { setQ = it },
                    label = { Text("パック名・セット記号(例: SV2a)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = numQ,
                        onValueChange = { numQ = it },
                        label = { Text("番号") },
                        singleLine = true,
                        keyboardOptions = numberKeyboard,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = denomQ,
                        onValueChange = { denomQ = it },
                        label = { Text("分母") },
                        singleLine = true,
                        keyboardOptions = numberKeyboard,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(8.dp))
                results.forEach { c ->
                    Button(
                        onClick = { onPick(c) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("${c.setId} ${c.setName} #${c.number} ${c.name}") }
                }
                if (results.size >= 30) Text("30件まで表示しています。条件を足して絞ってください")
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text("やめる") } }
    )
}
val DEFAULT_TAGS = listOf(
    "美品",
    "傷あり",
    "お気に入り",
    "コレクション用",
    "プレイ用",
    "未開封"
)


@Composable
fun SettingsScreen(
    scanIntervalMs: Int,
    onScanIntervalSave: (Int) -> Unit,
    tagOptions: List<String>,
    onAddTag: (String) -> Unit,
    onDeleteTag: (String) -> Unit,
    onMoveTag: (Int, Int) -> Unit
) {
    var intervalText by remember(scanIntervalMs) {
        mutableStateOf(scanIntervalMs.toString())
    }

    var newTag by remember { mutableStateOf("") }
    var tagToDelete by remember { mutableStateOf<String?>(null) }

    val intervalValue = intervalText.toIntOrNull()
    val intervalValid = intervalValue != null &&
            intervalValue in 10..5000

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = "設定",
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(Modifier.height(20.dp))

        Text(
            text = "カメラ読み取り間隔",
            style = MaterialTheme.typography.titleMedium
        )

        Text("読み取り処理を開始する間隔をミリ秒で指定します。")

        OutlinedTextField(
            value = intervalText,
            onValueChange = { intervalText = it },
            label = { Text("読み取り間隔（ms）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            text = "設定可能範囲：10〜5000ms",
            style = MaterialTheme.typography.bodySmall
        )

        TextButton(
            onClick = {
                if (intervalValid) {
                    onScanIntervalSave(intervalValue!!)
                }
            },
            enabled = intervalValid
        ) {
            Text("読み取り間隔を保存")
        }

        Spacer(Modifier.height(28.dp))

        Text(
            text = "タグ管理",
            style = MaterialTheme.typography.titleMedium
        )

        Text(
            text = "タグの追加・削除・表示順を管理します。",
            style = MaterialTheme.typography.bodySmall
        )

        OutlinedTextField(
            value = newTag,
            onValueChange = { newTag = it },
            label = { Text("追加するタグ名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        TextButton(
            onClick = {
                val tag = newTag.trim()
                if (tag.isNotEmpty() && tag !in tagOptions) {
                    onAddTag(tag)
                    newTag = ""
                }
            },
            enabled = newTag.trim().isNotEmpty() &&
                    newTag.trim() !in tagOptions
        ) {
            Text("タグを追加")
        }

        Spacer(Modifier.height(12.dp))

        Text("登録済みタグ：${tagOptions.size}件")

        tagOptions.forEachIndexed { index, tag ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = tag,
                    modifier = Modifier.weight(1f)
                )

                TextButton(
                    onClick = {
                        onMoveTag(index, index - 1)
                    },
                    enabled = index > 0
                ) {
                    Text("↑")
                }

                TextButton(
                    onClick = {
                        onMoveTag(index, index + 1)
                    },
                    enabled = index < tagOptions.lastIndex
                ) {
                    Text("↓")
                }

                TextButton(
                    onClick = {
                        tagToDelete = tag
                    }
                ) {
                    Text("削除")
                }
            }
        }
    }

    // タグ削除の確認ダイアログ
    tagToDelete?.let { tag ->
        AlertDialog(
            onDismissRequest = {
                tagToDelete = null
            },
            title = {
                Text("タグを削除しますか？")
            },
            text = {
                Text(
                    "「$tag」を削除します。\n" +
                            "すべてのコレクションに保存されているカード個体からも、このタグが削除されます。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteTag(tag)
                        tagToDelete = null
                    }
                ) {
                    Text("削除する")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        tagToDelete = null
                    }
                ) {
                    Text("キャンセル")
                }
            }
        )
    }
}
@Composable
fun SettingsScreen(
    scanIntervalMs: Int,
    onScanIntervalChange: (Int) -> Unit,
    tagOptions: List<String>,
    onTagOptionsChange: (List<String>) -> Unit
) {
    var intervalText by remember(scanIntervalMs) {
        mutableStateOf(scanIntervalMs.toString())
    }
    var newTag by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = "設定",
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(Modifier.height(20.dp))

        Text(
            text = "読み取り速度",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = intervalText,
            onValueChange = { intervalText = it },
            label = { Text("読み取り間隔（ミリ秒）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            text = "設定可能範囲：10〜5000ミリ秒",
            style = MaterialTheme.typography.bodySmall
        )

        TextButton(
            onClick = {
                val value = intervalText.toIntOrNull()

                if (value != null && value in 10..5000) {
                    onScanIntervalChange(value)
                    message = "読み取り間隔を保存しました"
                } else {
                    message = "10〜5000の整数を入力してください"
                }
            }
        ) {
            Text("読み取り間隔を保存")
        }

        if (message.isNotEmpty()) {
            Text(message)
        }

        Spacer(Modifier.height(24.dp))

        Text(
            text = "タグ管理",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = newTag,
            onValueChange = { newTag = it },
            label = { Text("新しいタグ名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        TextButton(
            onClick = {
                val tag = newTag.trim()

                when {
                    tag.isEmpty() -> {
                        message = "タグ名を入力してください"
                    }
                    tag in tagOptions -> {
                        message = "同じタグが既にあります"
                    }
                    else -> {
                        onTagOptionsChange(tagOptions + tag)
                        newTag = ""
                        message = "タグを追加しました"
                    }
                }
            }
        ) {
            Text("タグを追加")
        }

        Spacer(Modifier.height(8.dp))

        tagOptions.forEach { tag ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = tag,
                    modifier = Modifier.weight(1f)
                )

                TextButton(
                    onClick = {
                        onTagOptionsChange(tagOptions - tag)
                        message = "タグを削除しました"
                    }
                ) {
                    Text("削除")
                }
            }
        }
    }
}