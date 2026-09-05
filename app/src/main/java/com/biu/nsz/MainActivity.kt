package com.biu.nsz

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.OutputStream
import java.util.Locale

/**
 * Process-wide UI state for a running extraction. The worker coroutine in
 * appScope may outlive the Activity (backgrounding can destroy & recreate it),
 * so it must never hold View references — it publishes here instead, and the
 * *current* Activity instance re-attaches in onResume and replays the state.
 */
object ExtractBus {
    interface Listener {
        fun onState(status: String, perFile: Int, overall: Int, overallVisible: Boolean, running: Boolean)
    }

    @Volatile var running: Boolean = false
    @Volatile var status: String = ""
    @Volatile var perFile: Int = 0          // current file progress, 0..10000
    @Volatile var overall: Int = 0          // batch overall progress, 0..10000
    @Volatile var overallVisible: Boolean = false

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var listener: Listener? = null

    fun attach(l: Listener) {
        listener = l
        l.onState(status, perFile, overall, overallVisible, running)
    }

    fun detach(l: Listener) {
        if (listener === l) listener = null
    }

    fun postStatus(text: String) {
        status = text
        val l = listener ?: return
        main.post { l.onState(status, perFile, overall, overallVisible, running) }
    }

    fun postProgress(pf: Int, ov: Int, ovVisible: Boolean) {
        perFile = pf
        if (ovVisible) overall = ov
        overallVisible = ovVisible
        val l = listener ?: return
        main.post { l.onState(status, perFile, overall, overallVisible, running) }
    }

    fun postRunning(r: Boolean) {
        running = r
        val l = listener ?: return
        main.post { l.onState(status, perFile, overall, overallVisible, running) }
    }
}

class MainActivity : AppCompatActivity(), ExtractBus.Listener {

    /** One batch candidate: the file plus its path relative to the scanned root. */
    private data class BatchItem(val file: DocumentFile, val relPath: String)

    private var inputUri: Uri? = null      // single-file mode
    private var inputName: String? = null
    private var treeUri: Uri? = null       // output folder
    private var batchFolderUri: Uri? = null
    private var batchFiles: List<BatchItem> = emptyList()

    /** Scope independent from the Activity lifecycle so backgrounding / rotation
     *  does NOT cancel a running batch (root cause of "batch stops midway"). */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Cache of "already existing names" per output sub-folder, so conflict checks
     *  do not re-list the whole directory for every file (slow on big batches). */
    private val nameCache = HashMap<String, MutableSet<String>>()
    private val dirCache = HashMap<String, DocumentFile>()

    private lateinit var prefs: SharedPreferences
    private lateinit var statusText: TextView
    private lateinit var fileText: TextView
    private lateinit var folderText: TextView
    private lateinit var dirText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressOverall: ProgressBar
    private lateinit var labelOverall: TextView
    private lateinit var btnGo: Button
    private lateinit var btnPickFile: Button
    private lateinit var btnPickFolder: Button
    private lateinit var btnPickDir: Button

    private val pickFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                inputUri = uri
                inputName = queryDisplayName(uri)
                fileText.text = getString(R.string.selected_file, inputName ?: uri.lastPathSegment)
                if (NszDecoder.defaultOutputName(inputName ?: "") == null) {
                    statusText.text = getString(R.string.err_extension)
                }
                updateGoState()
            }
        }

    /** Input folder for batch mode: scan it recursively for .nsz / .ncz files. */
    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                batchFolderUri = uri
                folderText.text = getString(R.string.selected_folder,
                    queryDisplayName(uri) ?: uri.lastPathSegment ?: "", 0)
                lifecycleScope.launch {
                    val files = withContext(Dispatchers.IO) { scanFolder(uri) }
                    if (files.isEmpty()) {
                        batchFiles = emptyList()
                        folderText.text = getString(R.string.folder_none)
                        Toast.makeText(this@MainActivity, R.string.folder_none, Toast.LENGTH_SHORT).show()
                    } else {
                        batchFiles = files
                        folderText.text = getString(R.string.selected_folder,
                            queryDisplayName(uri) ?: uri.lastPathSegment ?: "", files.size)
                    }
                    updateGoState()
                }
            }
        }

    private val pickDir =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                treeUri = uri
                saveLastOutputDir(uri)
                dirText.text = getString(R.string.selected_dir, queryDisplayName(uri) ?: uri.lastPathSegment)
                updateGoState()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = layoutInflater.inflate(R.layout.activity_main, null) as LinearLayout
        setContentView(root)

        prefs = getSharedPreferences("settings", MODE_PRIVATE)

        btnPickFile = root.findViewById(R.id.btn_pick_file)
        btnPickFolder = root.findViewById(R.id.btn_pick_folder)
        btnPickDir = root.findViewById(R.id.btn_pick_dir)
        btnGo = root.findViewById(R.id.btn_go)
        fileText = root.findViewById(R.id.text_file)
        folderText = root.findViewById(R.id.text_folder)
        dirText = root.findViewById(R.id.text_dir)
        statusText = root.findViewById(R.id.text_status)
        progressBar = root.findViewById(R.id.progress)
        progressOverall = root.findViewById(R.id.progress_overall)
        labelOverall = root.findViewById(R.id.label_overall)

        btnPickFile.setOnClickListener {
            pickFile.launch(arrayOf("*/*"))
        }
        btnPickFolder.setOnClickListener {
            pickFolder.launch(null)
        }
        btnPickDir.setOnClickListener {
            pickDir.launch(null)
        }
        btnGo.setOnClickListener { startDecompress() }

        root.findViewById<TextView>(R.id.link_mac).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/newnight/nsz2nsp/releases")))
            } catch (_: Exception) {
                Toast.makeText(this, R.string.toast_no_browser, Toast.LENGTH_SHORT).show()
            }
        }

        restoreLastOutputDir()
        updateGoState()
    }

    override fun onResume() {
        super.onResume()
        // re-attach after recreation and replay the live extraction state,
        // so the bars keep moving instead of freezing at stale values
        ExtractBus.attach(this)
    }

    override fun onPause() {
        ExtractBus.detach(this)
        super.onPause()
    }

    override fun onState(status: String, perFile: Int, overall: Int, overallVisible: Boolean, running: Boolean) {
        if (status.isNotEmpty()) statusText.text = status
        progressBar.max = 10000
        progressBar.progress = perFile
        labelOverall.visibility = if (overallVisible) View.VISIBLE else View.GONE
        progressOverall.visibility = if (overallVisible) View.VISIBLE else View.GONE
        progressOverall.max = 10000
        progressOverall.progress = overall
        updateGoState()
    }

    /** Reuse the last output folder if its persistable grant is still valid. */
    private fun restoreLastOutputDir() {
        val saved = prefs.getString(KEY_OUTPUT_TREE, null) ?: return
        val uri = Uri.parse(saved)
        val stillGranted = contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
        if (!stillGranted) return
        treeUri = uri
        dirText.text = getString(R.string.restored_dir, queryDisplayName(uri) ?: uri.lastPathSegment)
    }

    private fun saveLastOutputDir(uri: Uri) {
        prefs.edit().putString(KEY_OUTPUT_TREE, uri.toString()).apply()
    }

    /** Recursively collect .nsz / .ncz files under the picked tree,
     *  remembering each file's path relative to the root (for structure-preserving output). */
    private fun scanFolder(root: Uri): List<BatchItem> {
        val out = ArrayList<BatchItem>()
        data class Node(val dir: DocumentFile, val rel: String)
        val stack = ArrayDeque<Node>()
        DocumentFile.fromTreeUri(this, root)?.let { stack.addLast(Node(it, "")) }
        while (stack.isNotEmpty()) {
            val (dir, rel) = stack.removeLast()
            for (f in dir.listFiles()) {
                if (f.isDirectory) {
                    val childRel = if (rel.isEmpty()) (f.name ?: "") else "$rel/${f.name}"
                    stack.addLast(Node(f, childRel))
                } else {
                    val name = f.name?.lowercase(Locale.US) ?: continue
                    if (name.endsWith(".nsz") || name.endsWith(".ncz")) out.add(BatchItem(f, rel))
                }
            }
        }
        return out.sortedBy { (it.relPath + "/" + (it.file.name ?: "")).lowercase(Locale.US) }
    }

    private fun queryDisplayName(uri: Uri): String? =
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        } catch (e: Exception) {
            null
        }

    private fun updateGoState() {
        val hasInput = inputUri != null || batchFiles.isNotEmpty()
        btnGo.isEnabled = hasInput && !ExtractBus.running
    }

    private fun startDecompress() {
        if (ExtractBus.running) return

        // Single-file mode: need an explicit output folder (last used counts).
        if (inputUri != null && treeUri == null) {
            Toast.makeText(this, R.string.need_output, Toast.LENGTH_LONG).show()
            return
        }

        val single: Pair<Uri, String>? = inputUri?.let { it to (inputName ?: return) }
        val batch = batchFiles.toList()
        val outTree = treeUri ?: batchFolderUri // batch defaults to the scanned folder itself

        ExtractBus.postRunning(true)
        updateGoState()
        nameCache.clear()
        dirCache.clear()
        ExtractBus.postStatus(getString(R.string.status_preparing))

        val isBatch = single == null
        ExtractBus.postProgress(0, 0, isBatch)

        // appScope (not lifecycleScope): keeps running when the app goes to
        // background / screen locks / the activity is recreated.
        appScope.launch {
            var okCount = 0
            val failures = ArrayList<String>()
            try {
                if (single != null) {
                    val summary = withContext(Dispatchers.IO) {
                        decompressTo(single.first, single.second, outTree!!, null, null, 0, 1)
                    }
                    postStatus(if (summary == null) getString(R.string.status_skipped)
                               else getString(R.string.status_done, summary))
                    postToast()
                } else {
                    val total = batch.size
                    var okCount = 0
                    var skippedCount = 0
                    batch.forEachIndexed { i, item ->
                        val idx = i + 1
                        val shownName = item.relPath.ifEmpty { item.file.name ?: "?" }
                        postStatus(getString(R.string.batch_progress, idx, total, shownName))
                        try {
                            val out = withContext(Dispatchers.IO) {
                                decompressTo(
                                    item.file.uri, item.file.name ?: "unknown.nsz",
                                    outTree!!, item.relPath.ifEmpty { null },
                                    idx to total, idx, total
                                )
                            }
                            if (out == null) {
                                skippedCount++
                                postStatus(getString(R.string.batch_skipped, shownName))
                            } else {
                                okCount++
                            }
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (e: Exception) {
                            val msg = getString(R.string.batch_failed, shownName, e.message ?: e.toString())
                            failures.add(msg)
                            postStatus(msg)
                        }
                        // overall bar: snap to the completed-file boundary
                        ExtractBus.postProgress(ExtractBus.perFile, idx * 10000 / total, true)
                    }
                    val outName = queryDisplayName(outTree!!) ?: "selected folder"
                    val summary = getString(R.string.batch_summary_done,
                        okCount, total, skippedCount, failures.size, outName)
                    postStatus(if (failures.isEmpty()) summary else summary + "\n" + failures.joinToString("\n"))
                    postToast()
                }
            } catch (e: Exception) {
                postStatus(getString(R.string.status_error, e.message ?: e.toString()))
            } finally {
                ExtractBus.postRunning(false)
            }
        }
    }

    /** Resolve "同名文件按时间重新命名" inside the picked tree, then run the decoder.
     *  relPath: sub-folder (relative to the output tree) that must exist in the output, or null.
     *  batchIdx/batchTotal: (i, n) when running in batch mode, 0/1 for single-file mode. */
    private fun decompressTo(
        inUri: Uri, inName: String, tUri: Uri, relPath: String?,
        batchIdx: Pair<Int, Int>?, batchPos: Int, batchTotal: Int
    ): String? {
        val outRoot = DocumentFile.fromTreeUri(this, tUri)
            ?: throw NszException("cannot open output folder")

        // structure-preserving output: create/look up sub-folders (cached per batch run)
        val dir = if (relPath.isNullOrBlank()) outRoot else {
            dirCache.getOrPut(relPath) {
                var cur = outRoot
                for (seg in relPath.split('/').filter { it.isNotBlank() }) {
                    cur = cur.findFile(seg)?.takeIf { it.isDirectory }
                        ?: cur.createDirectory(seg)
                        ?: throw NszException("cannot create folder: $seg")
                }
                cur
            }
        }

        val cacheKey = relPath ?: ""
        val names = nameCache.getOrPut(cacheKey) {
            dir.listFiles().mapTo(HashSet()) { it.name ?: "" }
        }

        // already extracted: report as skipped (null) instead of renaming / overwriting
        val originalName = NszDecoder.defaultOutputName(inName)
            ?: throw NszException("only .nsz / .ncz files are supported")
        if (originalName in names) return null
        val target = originalName

        val outFile = dir.createFile("application/octet-stream", target)
            ?: throw NszException("cannot create output file in the selected folder")
        names.add(target)

        contentResolver.openFileDescriptor(inUri, "r")?.use { pfd ->
            val channel = FileInputStream(pfd.fileDescriptor).channel
            val reader = ChannelReader(channel)
            contentResolver.openOutputStream(outFile.uri)?.use { os ->
                var lastUiUpdate = 0L
                val label: (String) -> String = { name ->
                    if (batchIdx != null) getString(R.string.batch_progress, batchIdx.first, batchIdx.second, name)
                    else getString(R.string.status_extracting, name)
                }
                NszDecoder.decompress(reader, object : OutputStream() {
                    override fun write(b: Int) = throw UnsupportedOperationException()
                    override fun write(b: ByteArray, off: Int, len: Int) = os.write(b, off, len)
                }, skipScan = false) { ev ->
                    when (ev) {
                        is DecompressEvent.ScanWarning ->
                            postStatus(getString(R.string.scan_warning, ev.name, ev.runs.size,
                                    Formatter.formatShortFileSize(this, ev.runs.sumOf { it.length })))
                        is DecompressEvent.EntryStart ->
                            postStatus(label(ev.name))
                        is DecompressEvent.Progress -> {
                            val now = System.currentTimeMillis()
                            if (now - lastUiUpdate > 100 || ev.written == ev.total) {
                                lastUiUpdate = now
                                val frac = if (ev.total > 0) ev.written.toDouble() / ev.total else 0.0
                                // per-file bar, scaled 0..10000 to avoid Int overflow
                                val pf = (frac * 10000).toInt().coerceIn(0, 10000)
                                // overall bar: completed files + current file fraction
                                val ov = if (batchTotal > 1)
                                    (((batchPos - 1) + frac) * 10000 / batchTotal).toInt().coerceIn(0, 10000)
                                else pf
                                ExtractBus.postProgress(pf, ov, batchTotal > 1)
                            }
                        }
                        is DecompressEvent.EntryDone ->
                            postStatus(
                                if (ev.verified) getString(R.string.entry_verified, ev.name)
                                else getString(R.string.entry_mismatch, ev.name)
                            )
                        is DecompressEvent.EntryCopied ->
                            postStatus(getString(R.string.entry_copied, ev.name))
                        is DecompressEvent.Done -> Unit
                    }
                }
            } ?: throw NszException("cannot open output stream")
        } ?: throw NszException("cannot open input file")

        return target
    }

    private fun postStatus(text: String) {
        ExtractBus.postStatus(text)
    }

    private fun postToast() {
        runOnUiThread { Toast.makeText(this, R.string.toast_done, Toast.LENGTH_SHORT).show() }
    }

    companion object {
        private const val KEY_OUTPUT_TREE = "last_output_tree"
    }
}
