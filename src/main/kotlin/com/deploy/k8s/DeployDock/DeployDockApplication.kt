package com.deploy.k8s.DeployDock

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class DeployDockApplication

fun main(args: Array<String>) {
	runApplication<DeployDockApplication>(*args)
}
