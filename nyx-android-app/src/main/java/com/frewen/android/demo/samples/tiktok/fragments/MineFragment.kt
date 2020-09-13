package com.frewen.android.demo.samples.tiktok.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.ViewModelProviders
import androidx.lifecycle.observe
import com.frewen.android.demo.R
import com.frewen.aura.framework.fragment.BaseFragment

/**
 * @filename: HomeFragment
 * @introduction:
 * @author: Frewen.Wong
 * @time: 2020/9/3 18:57
 * @version: 1.0.0
 * @copyright: Copyright ©2020 Frewen.Wong. All Rights Reserved.
 */
class MineFragment : BaseFragment() {

    private lateinit var mineViewModel: MineViewModel

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        mineViewModel = ViewModelProviders.of(this).get(MineViewModel::class.java)
    }

    override fun getLayoutId(): Int {
        return R.layout.fragment_tiktok_mine
    }


}