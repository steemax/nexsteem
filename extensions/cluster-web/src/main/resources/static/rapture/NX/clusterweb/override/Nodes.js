/* SPDX-License-Identifier: EPL-1.0 */
/*global Ext, NX */
Ext.define('NX.clusterweb.override.Nodes', {
  override: 'NX.coreui.view.system.Nodes',
  requires: ['NX.State', 'NX.clusterweb.view.NodesDashboard'],
  initComponent: function() {
    if (NX.State.getValue('nxClusterEnabled') !== true) { this.callParent(arguments); return; }
    this.iconName = 'node-default';
    this.masters = [{xtype: 'nx-clusterweb-nodes-dashboard'}];
    this.skipDetail = true;
    this.tabs = null;
    this.html = null;
    // Skip the original licensed Nodes init; retain the standard Drilldown frame.
    this.callSuper(arguments);
  }
});
