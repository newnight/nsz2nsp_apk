package com.biu.nsz

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
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private var inputUri: Uri? = null
    private var inputName: String? = null
    private var treeUri: Uri? = null

    private lateinit var statusText: TextView
    private lateinit var fileText: TextView
    private lateinit var dirText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnGo: Button
    private lateinit var btnPickFile: Button
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

    private val pickDir =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                treeUri = uri
                dirText.text = getString(R.string.selected_dir, queryDisplayName(uri) ?: uri.lastPathSegment)
                updateGoState()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = layoutInflater.inflate(R.layout.activity_main, null) as LinearLayout
        setContentView(root)

        btnPickFile = root.findViewById(R.id.btn_pick_file)
        btnPickDir = root.findViewById(R.id.btn_pick_dir)
        btnGo = root.findViewById(R.id.btn_go)
        fileText = root.findViewById(R.id.text_file)
        dirText = root.findViewById(R.id.text_dir)
        statusText = root.findViewById(R.id.text_status)
        progressBar = root.findViewById(R.id.progress)

        btnPickFile.setOnClickListener {
            pickFile.launch(arrayOf("*/*"))
        }
        btnPickDir.setOnClickListener {
            pickDir.launch(null)
        }
        btnGo.setOnClickListener { startDecompress() }
        updateGoState()
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
        btnGo.isEnabled = inputUri != null && treeUri != null && !isRunning
    }

    private var isRunning = false

    private fun startDecompress() {
        val inUri = inputUri ?: return
        val tUri = treeUri ?: return
        val name = inputName ?: return
        val outName0 = NszDecoder.defaultOutputName(name) ?: run {
            Toast.makeText(this, R.string.err_extension, Toast.LENGTH_LONG).show()
            return
        }

        isRunning = true
        updateGoState()
        progressBar.progress = 0
        statusText.text = getString(R.string.status_preparing)

        lifecycleScope.launch {
            try {
                val summary = withContext(Dispatchers.IO) {
                    decompressTo(inUri, name, tUri, outName0)
                }
                statusText.text = getString(R.string.status_done, summary)
                Toast.makeText(this@MainActivity, R.string.toast_done, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                statusText.text = getString(R.string.status_error, e.message ?: e.toString())
            } finally {
                isRunning = false
                updateGoState()
            }
        }
    }

    /** Resolve "同名文件按时间重新命名" inside the picked tree, then run the decoder. */
    private fun decompressTo(inUri: Uri, inName: String, tUri: Uri, outName0: String): String {
        val dir = DocumentFile.fromTreeUri(this, tUri)
            ?: throw NszException("cannot open output folder")

        // resolve conflict: Name.nsp -> "Name yyyy-MM-dd HH.mm.ss.nsp" -> " (2)"...
        var target = outName0
        if (dir.findFile(target) != null) {
            val base = outName0.substringBeforeLast('.')
            val ext = outName0.substringAfterLast('.')
            val stamp = SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US).format(Date())
            var n = 2
            var candidate = "$base $stamp.$ext"
            while (dir.findFile(candidate) != null) {
                candidate = "$base $stamp ($n).$ext"
                n++
            }
            target = candidate
        }
        val renamed = target != outName0

        val outFile = dir.createFile("application/octet-stream", target)
            ?: throw NszException("cannot create output file in the selected folder")

        contentResolver.openFileDescriptor(inUri, "r")?.use { pfd ->
            val channel = FileInputStream(pfd.fileDescriptor).channel
            val reader = ChannelReader(channel)
            contentResolver.openOutputStream(outFile.uri)?.use { os ->
                var lastUiUpdate = 0L
                NszDecoder.decompress(reader, object : OutputStream() {
                    override fun write(b: Int) = throw UnsupportedOperationException()
                    override fun write(b: ByteArray, off: Int, len: Int) = os.write(b, off, len)
                }, skipScan = false) { ev ->
                    when (ev) {
                        is DecompressEvent.ScanWarning ->
                            postStatus(getString(R.string.scan_warning, ev.name, ev.runs.size,
                                    Formatter.formatShortFileSize(this, ev.runs.sumOf { it.length })))
                        is DecompressEvent.EntryStart ->
                            postStatus(getString(R.string.status_extracting, ev.name))
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
}
