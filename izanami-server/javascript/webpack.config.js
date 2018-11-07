const webpack = require('webpack');
const path = require('path');
const UglifyJsPlugin = require('uglifyjs-webpack-plugin');


const isDev = process.env.NODE_ENV !== 'production';

const plugins = [
  new webpack.DefinePlugin({
    '__DEV__': process.env.NODE_ENV === 'production',
    'process.env': {
      NODE_ENV: JSON.stringify(process.env.NODE_ENV || 'dev')
    }
  })
];

if (isDev) {
  plugins.push(new webpack.HotModuleReplacementPlugin());
  plugins.push(new webpack.NoEmitOnErrorsPlugin());
}

const commons = {
  optimization: {
    minimize: true
  },
  output: {
    path: path.resolve(__dirname, '../public/bundle/'),
    publicPath: '/assets/bundle/',
    filename: '[name].js',
    library: '[name]',
    libraryTarget: 'umd'
  },
  entry: {
    Izanami: './src/izanami.js',
  },
  resolve: {
    extensions: ['*', '.js', '.css', '.scss']
  },
  devServer: {
    port: process.env.DEV_SERVER_PORT || 3333
  },
  module: {
    rules: [
      {
        test: /\.js|\.jsx|\.es6$/,
        exclude: /node_modules/,
        use: {
          loader: 'babel-loader'
        }
      },
      {
        test: /\.woff(2)?(\?v=[0-9]\.[0-9]\.[0-9])?$/,
        use: [
          {
            loader: 'url-loader',
            options: {
              limit: 10000,
              minetype: 'application/font-woff'
            }
          }
        ]
      },
      {
        test: /\.(ttf|eot|svg)(\?v=[0-9]\.[0-9]\.[0-9])?$/,
        use: [
          {
            loader: 'file-loader',
            options: {}
          }
        ]
      },
      {
        test: /\.scss$/,
        use: [
          { loader: "style-loader" },
          { loader: "css-loader" },
          { loader: "sass-loader" }
        ]
      },
      {
        test: /\.css$/,
        exclude: /\.useable\.css$/,
        use: [
          {
            loader: "style-loader"
          },
          { loader: "css-loader" },
        ],
      },
      {
        test: /\.useable\.css$/,
        use: [
          {
            loader: "style-loader/useable"
          },
          { loader: "css-loader" },
        ],
      }
    ]
  },
  plugins: plugins
};


if (isDev) {
  module.exports = { ...commons, devtool: 'inline-source-map' };
} else {
  module.exports = {...commons, devtool: false};
}


