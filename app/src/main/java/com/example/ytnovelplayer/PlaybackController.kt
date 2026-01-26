package com.example.ytnovelplayer

object PlaybackController {
    var listener: Callback? = null
    
    interface Callback {
        fun onControllerPlay()
        fun onControllerPause()
        fun onControllerSeekForward()
        fun onControllerSeekBackward()
    }
    
    fun triggerPlay() { listener?.onControllerPlay() }
    fun triggerPause() { listener?.onControllerPause() }
    fun triggerSeekForward() { listener?.onControllerSeekForward() }
    fun triggerSeekBackward() { listener?.onControllerSeekBackward() }
}
