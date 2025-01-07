package com.houvven.ktx_xposed

import de.robv.android.xposed.callbacks.XC_LoadPackage

interface LoadPackageHookAdapter {

    fun onHook();
    fun onHook(lpparam: XC_LoadPackage.LoadPackageParam) = onHook()

}