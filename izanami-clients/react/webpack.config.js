const webpack = require('webpack');
const path = require('path');

const plugins = [
  //webpack.optimize.OccurenceOrderPlugin(),
  new webpack.DefinePlugin({
    '__DEV__': process.env.NODE_ENV === 'production',
    'process.env': {
      NODE_ENV: JSON.stringify(process.env.NODE_ENV || 'dev')
    }
  })
];

if (process.env.NODE_ENV === 'production') {
  plugins.push(new webpack.optimize.UglifyJsPlugin({
    compress: {
      screw_ie8: true, // React doesn't support IE8
      warnings: false
    },
    mangle: {
      screw_ie8: true
    },
    output: {
      comments: false,
      screw_ie8: true
    }
  }));
} else {
  plugins.push(new webpack.HotModuleReplacementPlugin());
  plugins.push(new webpack.NoEmitOnErrorsPlugin());
}

module.exports = {
  output: {
    path: path.resolve(__dirname, './dist/'),
    publicPath: '/assets/',
    filename: 'izanami-[name].js',
    library: 'Izanami',
    libraryTarget: 'umd'
  },
  entry: {
    test: './src/test.js',
    client: './src/index.js'
  },
  resolve: {
    extensions: ['*', '.js']
  },
  devServer: {
    port: process.env.DEV_SERVER_PORT || 3000,
    proxy: {
      '/api/*': {
        target: 'http://localhost:3200',
        pathRewrite: { '^/api': '' },
        secure: false,
        logLevel: 'debug'
      }
    }
  },
  module: {
    rules: [
      {
        test: /\.js|\.jsx|\.es6$/,
        exclude: /node_modules/,
        use: {
          loader: 'babel-loader',
          options: {
              presets: ['@babel/preset-env', '@babel/react'],
              plugins: [
                  "@babel/plugin-proposal-class-properties",
                  ["@babel/plugin-proposal-decorators", {"legacy": true}],
                  "@babel/plugin-proposal-do-expressions",
                  "@babel/plugin-proposal-export-default-from",
                  "@babel/plugin-proposal-export-namespace-from",
                  "@babel/plugin-proposal-function-bind",
                  "@babel/plugin-proposal-function-sent",
                  "@babel/plugin-proposal-json-strings",
                  "@babel/plugin-proposal-logical-assignment-operators",
                  "@babel/plugin-proposal-nullish-coalescing-operator",
                  "@babel/plugin-proposal-numeric-separator",
                  "@babel/plugin-proposal-object-rest-spread",
                  "@babel/plugin-proposal-optional-chaining",
                  ["@babel/plugin-proposal-pipeline-operator", {"proposal": "minimal"}],
                  "@babel/plugin-proposal-throw-expressions",
                  "@babel/plugin-syntax-dynamic-import",
                  "@babel/plugin-syntax-import-meta"
              ]
          }
        }
      }
    ]
  },
  plugins: plugins
};
