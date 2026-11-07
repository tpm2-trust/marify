package app.marify.vm

/**
 * A test that only tpm2-kira's tools/phone-vm-test.py runs, against a VM over simulated Bluetooth
 * (it starts the class by name); every Gradle run leaves it out (notAnnotation, app/build.gradle.kts).
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class RunByPhoneVmTest
