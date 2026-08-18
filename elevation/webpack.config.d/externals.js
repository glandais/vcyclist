// Mark @jsquash/webp as a Node-only dependency: do not bundle it into a browser webpack
// build. The Node target resolves it at runtime via eval('require'); a browser build never
// enters that code path. Since the demo executable was dropped, the remaining browser webpack
// run in this module is the Karma test bundle (`jsBrowserTest`) — which still needs this.
config.externals = (config.externals || []).concat([
    function ({ request }, callback) {
        if (request && request.startsWith('@jsquash/webp')) {
            return callback(null, 'commonjs ' + request);
        }
        callback();
    },
]);
