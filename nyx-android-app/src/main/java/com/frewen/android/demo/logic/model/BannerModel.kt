package com.frewen.android.demo.logic.model

import android.annotation.SuppressLint
import android.os.Parcelable
import kotlinx.android.parcel.Parcelize

/**
 * 首恶的轮播图
 */
@SuppressLint("ParcelCreator")
@Parcelize
data class BannerModel(
        var desc: String = "",
        var id: Int = 0,
        var imagePath: String = "",
        var isVisible: Int = 0,
        var order: Int = 0,
        var title: String = "",
        var type: Int = 0,
        var url: String = ""
) : Parcelable


