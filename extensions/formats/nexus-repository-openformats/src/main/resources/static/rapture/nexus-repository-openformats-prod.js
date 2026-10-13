/* SPDX-License-Identifier: EPL-1.0 */
Ext.define('NX.openformats.app.PluginConfig', {
  extend: 'NX.app.PluginConfig',
  requires: [
    'NX.coreui.view.repository.recipe.CargoHosted', 'NX.coreui.view.repository.recipe.CargoProxy', 'NX.coreui.view.repository.recipe.CargoGroup',
    'NX.coreui.view.repository.recipe.GitLfsHosted',
    'NX.coreui.view.repository.recipe.GolangProxy', 'NX.coreui.view.repository.recipe.GolangGroup',
    'NX.coreui.view.repository.recipe.ComposerProxy', 'NX.coreui.view.repository.recipe.HuggingFaceProxy',
    'NX.coreui.view.repository.recipe.SwiftHosted', 'NX.coreui.view.repository.recipe.SwiftProxy', 'NX.coreui.view.repository.recipe.SwiftGroup',
    'NX.coreui.view.repository.recipe.TerraformHosted', 'NX.coreui.view.repository.recipe.TerraformProxy', 'NX.coreui.view.repository.recipe.TerraformGroup',
    'NX.coreui.view.repository.recipe.NugetHosted', 'NX.coreui.view.repository.recipe.NugetProxy', 'NX.coreui.view.repository.recipe.NugetGroup'
  ]
});
