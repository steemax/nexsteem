/* SPDX-License-Identifier: EPL-1.0 */
/*global Ext */
Ext.define('NX.clusterweb.app.PluginConfig', {
  extend: 'NX.app.PluginConfig',
  requires: ['NX.clusterweb.util.NodesPresentation', 'NX.clusterweb.view.NodesDashboard',
    'NX.clusterweb.override.Nodes', 'NX.clusterweb.override.NodesController']
});
