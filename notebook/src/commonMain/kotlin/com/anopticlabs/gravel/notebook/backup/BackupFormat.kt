package com.anopticlabs.gravel.notebook.backup

/** Why an archive could not be opened; the UI words each one. */
enum class BackupProblem {
    NOT_A_BACKUP,
    NEWER_FORMAT,
    DAMAGED,
    /** A failed authentication tag: a wrong password and a damaged archive look the same. */
    WRONG_PASSWORD_OR_DAMAGED,
}

class InvalidBackupException(val problem: BackupProblem, message: String) : RuntimeException(message)
