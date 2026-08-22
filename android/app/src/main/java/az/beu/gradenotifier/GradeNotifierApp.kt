package az.beu.gradenotifier

import android.app.Application
import az.beu.gradenotifier.notify.createChannels

class GradeNotifierApp : Application() {
    override fun onCreate() {
        super.onCreate()
        createChannels(this)
    }
}
