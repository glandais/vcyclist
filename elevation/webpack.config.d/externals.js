// Mark @jsquash/webp as a Node-only dependency: do not bundle it into the browser
// distribution. The Node target resolves it at runtime via eval('require'); the
// browser distribution never enters that code path.
config.externals = (config.externals || []).concat([
    function ({ request }, callback) {
        if (request && request.startsWith('@jsquash/webp')) {
            return callback(null, 'commonjs ' + request);
        }
        callback();
    },
]);
