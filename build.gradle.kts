plugins {
    java
    idea
    alias(libs.plugins.shadow)
    alias(libs.plugins.paper)
    alias(libs.plugins.runpaper)
}

group = project.properties["plugin.group"].toString()
version = project.properties["plugin.version"].toString()

repositories {
    mavenCentral()
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
    maven("https://repo.bluecolored.de/releases")
}

dependencies {
    val paperVersion = project.properties["paper.version"].toString()
    paperweight.foliaDevBundle(paperVersion)
    compileOnly("de.bluecolored.bluemap:BlueMapAPI:2.7.1")
}

tasks {
    assemble {
        dependsOn(reobfJar)
    }

    processResources {
        filteringCharset = Charsets.UTF_8.name()
        val props = mapOf(
            "name" to project.properties["plugin.name"],
            "version" to project.version,
            "main" to project.properties["plugin.main"],
            "apiVersion" to project.properties["paper.api"],
        )
        inputs.properties(props)
        filesMatching("paper-plugin.yml") {
            expand(props)
        }
    }
}

runPaper {
    folia {
        registerTask {
            val paperVersion = project.properties["paper.version"].toString()
            serverJar(file("run/folia-paperclip-${paperVersion}-mojmap.jar"))
        }
    }
}
