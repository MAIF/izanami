const webpack = require('webpack');
const path = require('path');

const plugins = [
  new webpack.DefinePlugin({
    '__DEV__': process.env.NODE_ENV === 'production',
    'process.env': {
      NODE_ENV: JSON.stringify(process.env.NODE_ENV || 'development')
    }
  })
];

if (process.env.NODE_ENV !== 'production') {
  plugins.push(new webpack.HotModuleReplacementPlugin());
  plugins.push(new webpack.NoEmitOnErrorsPlugin());
}

module.exports = {
  optimization: {
    minimize: true
  },
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
    port: process.env.DEV_SERVER_PORT || 3040,
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
          loader: 'babel-loader'
        }
      }
    ]
  },
  plugins: plugins
};
