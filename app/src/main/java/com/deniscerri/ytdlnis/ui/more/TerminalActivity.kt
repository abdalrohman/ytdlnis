package com.deniscerri.ytdlnis.ui.more

import android.app.ActionBar.LayoutParams
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.deniscerri.ytdlnis.R
import com.deniscerri.ytdlnis.database.viewmodel.CommandTemplateViewModel
import com.deniscerri.ytdlnis.ui.BaseActivity
import com.deniscerri.ytdlnis.util.FileUtil
import com.deniscerri.ytdlnis.util.NotificationUtil
import com.deniscerri.ytdlnis.util.UiUtil
import com.deniscerri.ytdlnis.util.UiUtil.enableTextHighlight
import com.deniscerri.ytdlnis.work.TerminalDownloadWorker
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomappbar.BottomAppBar
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.properties.Delegates


class TerminalActivity : BaseActivity() {
    private var topAppBar: MaterialToolbar? = null
    private lateinit var notificationUtil: NotificationUtil
    private var output: TextView? = null
    private var input: EditText? = null
    private var fab: ExtendedFloatingActionButton? = null
    private var scrollView: ScrollView? = null
    private lateinit var bottomAppBar: BottomAppBar
    private lateinit var commandTemplateViewModel: CommandTemplateViewModel
    private lateinit var sharedPreferences: SharedPreferences
    private var downloadID by Delegates.notNull<Int>()
    private lateinit var downloadFile : File
    private lateinit var imm : InputMethodManager
    private lateinit var metrics: DisplayMetrics
    var context: Context? = null

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)

        downloadID = savedInstanceState?.getInt("downloadID") ?: (System.currentTimeMillis().toInt() % 100000)
        downloadFile = File(cacheDir.absolutePath + "/$downloadID.txt")
        if (! downloadFile.exists()) downloadFile.createNewFile()
        imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager

        context = baseContext
        scrollView = findViewById(R.id.custom_command_scrollview)
        topAppBar = findViewById(R.id.custom_command_toolbar)
        topAppBar!!.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        commandTemplateViewModel = ViewModelProvider(this)[CommandTemplateViewModel::class.java]
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        metrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)


        bottomAppBar = findViewById(R.id.bottomAppBar)
        var templateCount = 0
        var shortcutCount = 0
        lifecycleScope.launch {
            templateCount = withContext(Dispatchers.IO){
                commandTemplateViewModel.getTotalNumber()
            }
            if (templateCount == 0){
                bottomAppBar.menu.getItem(0).icon?.alpha = 30
            }else{
                bottomAppBar.menu.getItem(0).icon?.alpha = 255
            }

            shortcutCount = withContext(Dispatchers.IO){
                commandTemplateViewModel.getTotalShortcutNumber()
            }
            if (shortcutCount == 0) {
                bottomAppBar.menu.getItem(1).icon?.alpha = 30
            }else{
                bottomAppBar.menu.getItem(1).icon?.alpha = 255
            }
        }
        bottomAppBar.setOnMenuItemClickListener {
            when(it.itemId){
                R.id.command_templates -> {
                    if (templateCount == 0){
                        Toast.makeText(context, getString(R.string.add_template_first), Toast.LENGTH_SHORT).show()
                    }else{
                        lifecycleScope.launch {
                            UiUtil.showCommandTemplates(this@TerminalActivity, commandTemplateViewModel){ templates ->
                                templates.forEach {c ->
                                    input!!.text.insert(input!!.selectionStart, c.content + " ")
                                }
                                input!!.postDelayed({
                                    input!!.requestFocus()
                                    imm.showSoftInput(input!!, 0)
                                }, 200)
                            }
                        }
                    }
                }
                R.id.shortcuts -> {
                    lifecycleScope.launch {
                        if (shortcutCount > 0){
                            UiUtil.showShortcuts(this@TerminalActivity, commandTemplateViewModel){sh ->
                                input!!.text.insert(input!!.selectionStart, "$sh ")
                            }
                        }
                    }
                }
                R.id.folder -> {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                    commandPathResultLauncher.launch(intent)
                }

            }
            true
        }

        output = findViewById(R.id.custom_command_output)
        output!!.setTextIsSelectable(true)
        output!!.layoutParams!!.width = LayoutParams.WRAP_CONTENT
        input = findViewById(R.id.command_edittext)
        input!!.requestFocus()
        fab = findViewById(R.id.command_fab)
        fab!!.setOnClickListener {
            if (fab!!.text == getString(R.string.run_command)){
                input!!.visibility = View.GONE
                output!!.text = "${output!!.text}\n~ $ ${input!!.text}\n"
                showCancelFab()
                imm.hideSoftInputFromWindow(input?.windowToken, 0)
                startDownload(
                    input!!.text.toString()
                )
            }else {
                cancelDownload()
                input!!.visibility = View.VISIBLE
                hideCancelFab()
            }
        }
        notificationUtil = NotificationUtil(this)
        handleIntent(intent)

        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(downloadID.toString())
            .observe(this){ list ->
                list.forEach {work ->
                    if (work.state == WorkInfo.State.SUCCEEDED || work.state == WorkInfo.State.FAILED || work.state == WorkInfo.State.CANCELLED) {
                        input!!.setText("yt-dlp ")
                        input!!.visibility = View.VISIBLE
                        input!!.requestFocus()
                        input!!.setSelection(input!!.text.length)
                        hideCancelFab()
                        downloadFile.writeText("")
                    }
                    val outputData = work.progress.getString("output")
                    if (!outputData.isNullOrBlank()){
                        runOnUiThread{
                            try {
                                output!!.append("\n$outputData")
                                output!!.scrollTo(0, output!!.height)
                                scrollView!!.fullScroll(View.FOCUS_DOWN)
                            }catch (ignored: Exception) {}
                        }
                    }

                }
            }
        initMenu()

        input?.enableTextHighlight()
        output?.enableTextHighlight()

        input?.setText(savedInstanceState?.getString("input") ?: input?.text)
        input!!.requestFocus()
        input!!.setSelection(input!!.text.length)
        output?.text = savedInstanceState?.getString("output") ?: output?.text
        if (savedInstanceState?.getBoolean("run") == true){
            showCancelFab()
        }
    }

    override fun onSaveInstanceState(outState: Bundle, outPersistentState: PersistableBundle) {
        super.onSaveInstanceState(outState)
        outState.putString("input", input?.text.toString())
        outState.putString("output", output?.text.toString())
        outState.putBoolean("run", fab!!.text == getString(R.string.run_command))
        outState.putInt("downloadID", downloadID)
    }

    private fun initMenu() {
        topAppBar?.setOnMenuItemClickListener { m: MenuItem ->
            when(m.itemId){
                R.id.wrap -> {
                    var scrollView = findViewById<HorizontalScrollView>(R.id.horizontalscroll_output)
                    if(scrollView != null){
                        val parent = (scrollView.parent as ViewGroup)
                        scrollView.removeAllViews()
                        parent.removeView(scrollView)
                        parent.addView(output, 0)
                    }else{
                        val parent = output?.parent as ViewGroup
                        parent.removeView(output)
                        scrollView = HorizontalScrollView(this)
                        scrollView.layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        scrollView.addView(output)
                        scrollView.id = R.id.horizontalscroll_output
                        parent.addView(scrollView, 0)
                    }
                }
                R.id.export_clipboard -> {
                    lifecycleScope.launch(Dispatchers.IO){
                        val clipboard: ClipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setText(output?.text)
                    }
                }
            }
            true
        }
    }

    override fun onDestroy() {
        downloadFile.delete()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val action = intent.action
        val type = intent.type
        Log.e(TAG, "$action $type")
        if (action == Intent.ACTION_SEND && type != null) {
            Log.e(TAG, action)
            val text = if (intent.getStringExtra(Intent.EXTRA_TEXT) == null){
                val uri = if (Build.VERSION.SDK_INT >= 33){
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                }else{
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                "-a \"${FileUtil.formatPath(uri?.path ?: "")}\""
            }else{
                intent.getStringExtra(Intent.EXTRA_TEXT)
            }
            val txt = "yt-dlp $text"
            input!!.setText(txt)
        }
    }


    private fun hideCancelFab() {
        fab!!.text = getString(R.string.run_command)
        fab!!.setIconResource(R.drawable.ic_baseline_keyboard_arrow_right_24)
    }
    private fun showCancelFab() {
        fab!!.text = getString(R.string.cancel_download)
        fab!!.setIconResource(R.drawable.ic_cancel)
    }

    private var commandPathResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let {
                contentResolver?.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            input!!.text.insert(input!!.selectionStart, FileUtil.formatPath(result.data?.data.toString()))
        }
    }

    private fun startDownload(command: String?) {
        val cmd = if (command!!.contains("yt-dlp")) command.replace("yt-dlp", "")
        else command

        val workRequest = OneTimeWorkRequestBuilder<TerminalDownloadWorker>()
            .setInputData(
                Data.Builder()
                    .putInt("id", downloadID)
                    .putString("command", cmd)
                    .build()
            )
            .addTag("terminal")
            .build()

        WorkManager.getInstance(this).beginUniqueWork(
            downloadID.toString(),
            ExistingWorkPolicy.KEEP,
            workRequest
        ).enqueue()
    }

    private fun cancelDownload() {
        YoutubeDL.getInstance().destroyProcessById(downloadID.toString())
        WorkManager.getInstance(this).cancelAllWorkByTag(downloadID.toString())
        notificationUtil.cancelDownloadNotification(downloadID)
    }

    companion object {
        private const val TAG = "CustomCommandActivity"
    }
}