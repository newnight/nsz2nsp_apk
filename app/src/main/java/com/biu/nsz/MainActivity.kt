package com.biu.nsz

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.text.format.Formatter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private var inputUri: Uri? = null      // single-file mode
    private var inputName: String? = null
    private var treeUri: Uri? = null       // output folder
    private var batchFolderUri: Uri? = null
    private var batchFiles: List<DocumentFile> = emptyList()

    private lateinit var prefs: SharedPreferences
    private lateinit var statusText: TextView
    private lateinit var fileText: TextView
    private lateinit var folderText: TextView
    private lateinit var dirText: TextView
    private lateinit var progressBar: ProgressBar
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

        restoreLastOutputDir()
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

    /** Recursively collect .nsz / .ncz files under the picked tree. */
    private fun scanFolder(root: Uri): List<DocumentFile> {
        val out = ArrayList<DocumentFile>()
        val stack = ArrayDeque<DocumentFile>()
        DocumentFile.fromTreeUri(this, root)?.let { stack.addLast(it) }
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            for (f in dir.listFiles()) {
                if (f.isDirectory) {
                    stack.addLast(f)
                } else {
                    val name = f.name?.lowercase(Locale.US) ?: continue
                    if (name.endsWith(".nsz") || name.endsWith(".ncz")) out.add(f)
                }
            }
        }
        return out.sortedBy { it.name?.lowercase(Locale.US) }
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
        btnGo.isEnabled = hasInput && !isRunning
    }

    private var isRunning = false

    private fun startDecompress() {
        if (isRunning) return

        // Single-file mode: need an explicit output folder (last used counts).
        if (inputUri != null && treeUri == null) {
            Toast.makeText(this, R.string.need_output, Toast.LENGTH_LONG).show()
            return
        }

        val single: Pair<Uri, String>? = inputUri?.let { it to (inputName ?: return) }
        val batch = batchFiles.toList()
        val outTree = treeUri ?: batchFolderUri // batch defaults to the scanned folder itself

        isRunning = true
        updateGoState()
        progressBar.progress = 0
        statusText.text = getString(R.string.status_preparing)

        lifecycleScope.launch {
            try {
                if (single != null) {
                    val summary = withContext(Dispatchers.IO) {
                        decompressTo(single.first, single.second, outTree!!, null)
                    }
                    statusText.text = getString(R.string.status_done, summary)
                } else {
                    val total = batch.size
                    batch.forEachIndexed { i, f ->
                        val idx = i + 1
                        postStatus(getString(R.string.batch_progress, idx, total, f.name ?: "?"))
                        withContext(Dispatchers.IO) {
                            decompressTo(f.uri, f.name ?: "unknown.nsz", outTree!!, idx to total)
                        }
                    }
                    statusText.text = getString(R.string.status_done,
                        getString(R.string.batch_progress, total, total, "全部完成"))
                }
                Toast.makeText(this@MainActivity, R.string.toast_done, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                statusText.text = getString(R.string.status_error, e.message ?: e.toString())
            } finally {
                isRunning = false
                updateGoState()
            }
        }
    }

    /** Resolve "同名文件按时间重新命名" inside the picked tree, then run the decoder.
     *  batchIdx: (i, n) when running in batch mode, null for single-file mode. */
    private fun decompressTo(inUri: Uri, inName: String, tUri: Uri, batchIdx: Pair<Int, Int>?): String {
        val dir = DocumentFile.fromTreeUri(this, tUri)
            ?: throw NszException("cannot open output folder")

        // resolve conflict: Name.nsp -> "Name yyyy-MM-dd HH.mm.ss.nsp" -> " (2)"...
        var target = NszDecoder.defaultOutputName(inName)
            ?: throw NszException("only .nsz / .ncz files are supported")
        if (dir.findFile(target) != null) {
            val base = target.substringBeforeLast('.')
            val ext = target.substringAfterLast('.')
            val stamp = SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US).format(Date())
            var n = 2
            var candidate = "$base $stamp.$ext"
            while (dir.findFile(candidate) != null) {
                candidate = "$base $stamp ($n).$ext"
                n++
            }
            target = candidate
        }
        val renamed = target != NszDecoder.defaultOutputName(inName)

        val outFile = dir.createFile("application/octet-stream", target)
            ?: throw NszException("cannot create output file in the selected folder")

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
                                runOnUiThread {
                                    // scale to 0..10000 to avoid Int overflow on big files
                                    progressBar.max = 10000
                                    progressBar.progress = if (ev.total > 0)
                                        ((ev.written * 10000) / ev.total).toInt().coerceIn(0, 10000) else 0
                                }
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

        return if (renamed) getString(R.string.summary_renamed, target) else target
    }

    private fun postStatus(text: String) {
        runOnUiThread { statusText.text = text }
    }

    companion object {
        private const val KEY_OUTPUT_TREE = "last_output_tree"
    }
}
