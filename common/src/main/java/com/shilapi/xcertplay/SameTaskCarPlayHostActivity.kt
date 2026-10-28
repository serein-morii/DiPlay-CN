package com.shilapi.xcertplay

/**
 * The CarPlay screen declared as it was up to 0.2.11: singleTask without the `.carplay` task affinity, so it opens
 * in DiPlay's own task. [CarPlayScreen] opens it instead of [CarPlayHostActivity] when the driver turns on
 * "Keep CarPlay in the launcher card" (#463, #406, #1003).
 */
class SameTaskCarPlayHostActivity : CarPlayHostActivity()
