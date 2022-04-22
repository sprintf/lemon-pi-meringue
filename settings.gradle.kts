rootProject.name = "meringue"
include("lemon-pi-protos")

pluginManagement {
    plugins {
        kotlin("jvm") version "1.6.21"
        kotlin("plugin.spring") version "1.6.21"
    }
}