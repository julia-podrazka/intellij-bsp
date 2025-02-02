package org.jetbrains.plugins.bsp.ui.widgets.tool.window.actions

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import com.intellij.execution.configurations.ConfigurationType
import org.jetbrains.plugins.bsp.ui.configuration.run.BspRunConfigurationType
import org.jetbrains.plugins.bsp.ui.widgets.tool.window.all.targets.BspAllTargetsWidgetBundle

internal class RunTargetAction : SideMenuRunnerAction(
        BspAllTargetsWidgetBundle.message("widget.run.target.popup.message")) {
  override fun getConfigurationType(): ConfigurationType = BspRunConfigurationType()

  override fun getName(target: BuildTargetIdentifier): String = "run ${target.uri}"
}
