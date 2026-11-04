package com.laradars.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.laradars.app.databinding.ActivityInfoBinding

/** How the program works: fines, warning periods, who pays. */
class InfoActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val b = ActivityInfoBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.back.setOnClickListener { finish() }
        val cameras = CameraRepository.load(this)
        b.statusLine.text = getString(
            R.string.info_status_line,
            cameras.count { it.isLive }, cameras.count { !it.isLive },
        )
        b.ladotLink.setOnClickListener {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://ladot.lacity.gov/speed-safety-system"))) }
        }
    }
}
