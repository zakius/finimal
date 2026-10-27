// Prints the java command line that `:desktop:run` executes, one argument per line, after building its jars.
// finimal-verify build uses it, so the repo's own build files stay untouched.
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
