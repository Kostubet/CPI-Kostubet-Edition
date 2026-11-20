plugins {
    id("com.android.application")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.cosmod.launcherglass"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.kostubet.cpi"
        minSdk = 31
        targetSdk = 35
        versionCode = 427
        versionName = "0.3.1"
    }

    signingConfigs {
        if (file("debug.keystore").isFile) create("release") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

dependencies {
    val couiVersion = "2.0.0"
    implementation(platform(libs.androidx.compose.bom))
    implementation("androidx.activity:activity:1.13.0")
    implementation("io.github.suqi8.coui.kmp:coui-ui-android:$couiVersion")
    implementation("io.github.suqi8.coui.kmp:coui-preference-android:$couiVersion")
    implementation("io.github.suqi8.coui.kmp:coui-icons-android:$couiVersion")
    compileOnly("de.robv.android.xposed:api:82")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")
    constraints {
        implementation("androidx.customview:customview:1.1.0")
        implementation("androidx.drawerlayout:drawerlayout:1.1.1")
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

val featureSearchOutput=layout.buildDirectory.dir("generated/featureSearch/java")
abstract class GenerateFeatureSearch:DefaultTask(){
    @get:OutputDirectory abstract val outputDirectory:DirectoryProperty
}
val generateFeatureSearch by tasks.registering(GenerateFeatureSearch::class) {
    outputDirectory.set(featureSearchOutput)
    val sources=fileTree("src/main/java/com/cosmod/launcherglass/ui"){include("**/*.kt")}
    inputs.files(sources);outputs.dir(featureSearchOutput)
    doLast {
        val source=file("src/main/java/com/cosmod/launcherglass/ui/MainActivity.kt").readText()
        val definitions=Regex("(?m)^    private fun (\\w+)\\s*\\(").findAll(source).toList()
        val bodies=definitions.mapIndexed {i,m->m.groupValues[1] to source.substring(m.range.first,definitions.getOrNull(i+1)?.range?.first ?: source.length)}.toMap()
        val featureRouting=bodies.getValue("buildFeaturePage").substringAfter("when(section){")
        val roots=Regex("(?m)^\\s*Screen\\.(\\w+)\\s*->\\s*(\\w+)\\(body(?:,\\s*\\w+)*\\)")
            .findAll(featureRouting).associate {it.groupValues[1] to it.groupValues[2]}
        val labels=Regex("(?<!android\\.)R\\.string\\.(\\w+)")
        val blocked=setOf("showPage","openScreen","openTab","backToCatalog","refreshCatalogStyle","save",
            "refreshControls","updateStatus","updateGlassPreview","informationRow")
        val auxiliary=sources.files.filter {it.name!="MainActivity.kt" && it.name!="FeatureSearch.kt"}.associate {it.nameWithoutExtension to it.readText()}
        val index=roots.mapValues {(_,root)->
            val result=sortedSetOf<String>();val seen=hashSetOf<String>()
            fun visit(name:String,depth:Int){
                if(depth>6 || name in blocked || !seen.add(name))return
                val body=bodies[name] ?: return
                labels.findAll(body).forEach {result.add(it.groupValues[1])}
                for((type,text) in auxiliary)if(Regex("\\b"+Regex.escape(type)+"\\b").containsMatchIn(body))labels.findAll(text).forEach {result.add(it.groupValues[1])}
                Regex("\\b(\\w+)\\s*\\(").findAll(body).forEach {visit(it.groupValues[1],depth+1)}
            }
            visit(root,0)
            result.filterNot {it.endsWith("_hint") || it.endsWith("_description") || it.endsWith("_keywords")}
        }
        val target=featureSearchOutput.get().file("com/cosmod/launcherglass/ui/GeneratedFeatureSearch.kt").asFile
        target.parentFile.mkdirs()
        target.writeText(buildString {
            appendLine("package com.cosmod.launcherglass.ui\nimport com.cosmod.launcherglass.R")
            appendLine("internal object GeneratedFeatureSearch { fun labels(section:String):IntArray=when(section){")
            index.toSortedMap().forEach {(section,ids)->appendLine("\"$section\"->intArrayOf("+ids.joinToString(","){"R.string.$it"}+")")}
            appendLine("else->intArrayOf()\n} }")
        })
    }
}
androidComponents.onVariants {variant->variant.sources.java?.addGeneratedSourceDirectory(generateFeatureSearch,GenerateFeatureSearch::outputDirectory)}
tasks.matching {it.name.startsWith("compile") && it.name.endsWith("Kotlin")}.configureEach {dependsOn(generateFeatureSearch)}
