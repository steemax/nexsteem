/* SPDX-License-Identifier: EPL-1.0 */
/*global Ext, NX */
Ext.define('NX.clusterweb.override.NodesController', {
  override: 'NX.coreui.controller.Nodes',
  requires: ['NX.State', 'NX.Permissions', 'NX.clusterweb.view.NodesDashboard'],
  init: function() {
    if (NX.State.getValue('nxClusterEnabled') === true) {
      this.features = Ext.apply({}, this.features);
      this.features.description = 'Cluster members, replication and PostgreSQL';
      this.features.visible = function() {
        return NX.State.getValue('nxClusterEnabled') === true && NX.Permissions.check('nexus:nodes:read');
      };
    }
    this.callParent(arguments);
  },
  loadStores: function() {
    if (NX.State.getValue('nxClusterEnabled') !== true) { this.callParent(arguments); return; }
    var feature = this.getFeature(), dashboard = feature && feature.down('nx-clusterweb-nodes-dashboard');
    if (dashboard) { dashboard.refreshStatus(); }
  },
  reselect: function() {
    if (NX.State.getValue('nxClusterEnabled') !== true) { this.callParent(arguments); }
  }
});
