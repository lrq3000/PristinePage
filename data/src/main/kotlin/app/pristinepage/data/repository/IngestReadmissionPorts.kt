package app.pristinepage.data.repository

import app.pristinepage.data.model.IngestJournalIntent

/** Called only after recovery holds fresh resource admission. The old recorded lease is a comparison/evidence snapshot, not permission to execute as that owner. A positive ENDED lookup must be available in metadata-only liveness; a restarted/forgotten owner stays blocked until independent process termination proof is populated outside Room. */
interface IngestReadmission {
    fun readmitEnded(intent: IngestJournalIntent, recorded: JobLease): Boolean
}
