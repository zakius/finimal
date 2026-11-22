allprojects {
    if (path == ":desktop") afterEvaluate {
        tasks.register("printRunCommand") {
            val run = tasks.named<JavaExec>("run").get()
            dependsOn(run.classpath, "prepareAppResources")
            doLast {
                println(run.javaLauncher.get().executablePath.asFile.path)
                run.allJvmArgs.forEach(::println)
                println("-cp"); println(run.classpath.asPath)
                println(run.mainClass.get())
                run.args.orEmpty().forEach(::println)
            }
        }
    }
}
