package tf.monochrome.recorder.editor

import tf.monochrome.recorder.editor.model.MediaRef
import tf.monochrome.recorder.editor.model.Project
import tf.monochrome.recorder.editor.model.addClips

const val SEC = 1_000_000L

fun video(name: String, seconds: Long, audio: Boolean = true, w: Int = 1080, h: Int = 2400) =
    MediaRef(uri = "content://test/$name", displayName = name, durationUs = seconds * SEC, width = w, height = h, hasVideo = true, hasAudio = audio)

fun music(name: String, seconds: Long) =
    MediaRef(uri = "content://test/$name", displayName = name, durationUs = seconds * SEC, hasVideo = false, hasAudio = true)

fun project(vararg clips: MediaRef) = Project(id = "p", name = "Test", createdAt = 0, updatedAt = 0).addClips(clips.toList())
