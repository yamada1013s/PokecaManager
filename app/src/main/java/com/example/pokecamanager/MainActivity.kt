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

data class SetInfo(val id: String, val name: String, val official: Int?)
data class CardRow(val setId: String, val setName: String, val number: Int?, val name: String)
class CardData(
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

class Store(private val prefs: SharedPreferences) {
    val counts = mutableStateMapOf<String, Int>()
    var lastCard by mutableStateOf<CardRow?>(null)
    var currentSetId by mutableStateOf<String?>(null)

    // 直近5件の登録履歴
    val history = mutableStateListOf<CardRow>()

    init {
        prefs.all.forEach { (k, v) ->
            if (v is Int && v > 0) counts[k] = v
        }
        loadHistory()
    }

    private fun loadHistory() {
        history.clear()
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
            }
        } catch (e: Exception) {
            history.clear()
        }
    }

    private fun saveHistory() {
        val array = JSONArray()

        history.take(5).forEach { card ->
            array.put(
                JSONObject()
                    .put("setId", card.setId)
                    .put("setName", card.setName)
                    .put("number", card.number ?: JSONObject.NULL)
                    .put("name", card.name)
            )
        }

        prefs.edit()
            .putString("registration_history", array.toString())
            .apply()
    }

    fun set(key: String, n: Int) {
        if (n <= 0) {
            counts.remove(key)
            prefs.edit().remove(key).apply()
        } else {
            counts[key] = n
            prefs.edit().putInt(key, n).apply()
        }
    }

    fun add(key: String, d: Int) {
        set(key, (counts[key] ?: 0) + d)
    }

    fun register(card: CardRow): Int {
        val k = keyOf(card)
        add(k, 1)
        lastCard = card

        history.add(0, card)
        while (history.size > 5) {
            history.removeAt(history.lastIndex)
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
        val key = keyOf(card)

        if ((counts[key] ?: 0) <= 0) return null

        add(key, -1)
        history.removeAt(index)

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

        if ((counts[oldKey] ?: 0) <= 0) return 0

        if (oldKey != newKey) {
            add(oldKey, -1)
            add(newKey, 1)
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
            if ((counts[oldKey] ?: 0) > 0) add(oldKey, -1)
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
    val dataResult = remember { runCatching { loadCardData(context) } }
    val data = dataResult.getOrNull()
    val lib = remember {
        Library(context.getSharedPreferences("collections", Context.MODE_PRIVATE))
    }
    val activeInfo = lib.items.firstOrNull { it.id == lib.activeId } ?: lib.items[0]
    val store = remember(lib.activeId) {
        Store(context.getSharedPreferences(prefsName(lib.activeId), Context.MODE_PRIVATE))
    }
    val nameMap = remember(data) {
        data?.cardsBySet?.values?.flatten()?.associateBy { keyOf(it) } ?: emptyMap()
    }
    var tab by remember { mutableStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> ScanScreen(owner, data, store, activeInfo.name, dataResult.exceptionOrNull()?.message)
                1 -> ListScreen(store, nameMap, activeInfo)
                2 -> ManualScreen(data, store, activeInfo.name)
                else -> ListsScreen(lib, store)
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
        }
    }
}

@Composable
@androidx.annotation.OptIn(ExperimentalGetImage::class)
fun ScanScreen(
    owner: LifecycleOwner, data: CardData?, store: Store, listName: String, loadError: String?
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
                            if (now - lastTime.get() < 30) {
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
                .offset(y = 0.dp)
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
fun ListScreen(store: Store, nameMap: Map<String, CardRow>, info: CollectionInfo) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var confirmKey by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("") }

    val entries = store.counts.entries
        .map { (k, v) -> toEntry(k, v, nameMap) }
        .sortedWith(compareBy({ it.setId }, { it.number ?: Int.MAX_VALUE }, { it.name }))
    val filtered = if (query.isBlank()) entries else entries.filter {
        "${it.setId} ${it.setName} ${it.number ?: ""} ${it.name}"
            .contains(query.trim(), ignoreCase = true)
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
                        OutlinedButton(onClick = {
                            if (e.count > 1) store.add(e.key, -1)
                        }) { Text("−") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { store.add(e.key, 1) }) { Text("＋") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { confirmKey = e.key }) { Text("削除") }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }

    confirmKey?.let { k ->
        AlertDialog(
            onDismissRequest = { confirmKey = null },
            title = { Text("削除しますか?") },
            text = { Text(nameMap[k]?.name ?: k) },
            confirmButton = {
                TextButton(onClick = {
                    store.set(k, 0)
                    confirmKey = null
                }) { Text("削除する") }
            },
            dismissButton = {
                TextButton(onClick = { confirmKey = null }) { Text("やめる") }
            }
        )
    }
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