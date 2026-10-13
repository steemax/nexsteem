/* SPDX-License-Identifier: EPL-1.0 */
Ext.define('NX.rubyyum.app.PluginConfig', {
  extend: 'NX.app.PluginConfig',
  requires: [
    'NX.coreui.view.repository.recipe.RubygemsHosted',
    'NX.coreui.view.repository.recipe.RubygemsProxy',
    'NX.coreui.view.repository.recipe.RubygemsGroup',
    'NX.coreui.view.repository.recipe.YumHosted',
    'NX.coreui.view.repository.recipe.YumProxy',
    'NX.coreui.view.repository.recipe.YumGroup'
  ]
});
