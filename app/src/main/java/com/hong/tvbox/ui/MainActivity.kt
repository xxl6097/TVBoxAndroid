package com.hong.tvbox.ui

import android.os.Bundle
import androidx.fragment.app.FragmentActivity

/**
 * 主界面 - 托管 Leanback BrowseFragment
 */
class MainActivity : FragmentActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, MainBrowseFragment())
                .commit()
        }
    }
}
