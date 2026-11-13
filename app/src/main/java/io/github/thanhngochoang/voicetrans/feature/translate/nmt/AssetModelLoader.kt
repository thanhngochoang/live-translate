package io.github.thanhngochoang.voicetrans.feature.translate.nmt

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.res.AssetManager
import java.io.FileInputStream
import java.nio.channels.FileChannel

object AssetModelLoader {
    fun session(
        env: OrtEnvironment,
        assets: AssetManager,
        path: String,
        opts: OrtSession.SessionOptions
    ): OrtSession {
        val fd = assets.openFd(path)
        val channel = FileInputStream(fd.fileDescriptor).channel
        val buffer = channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.length)
        return env.createSession(buffer, opts)
    }
}
