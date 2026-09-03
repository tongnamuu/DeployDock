package com.deploy.k8s.DeployDock

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class DeployDockApplication

fun main(args: Array<String>) {
	runApplication<DeployDockApplication>(*args)
}
