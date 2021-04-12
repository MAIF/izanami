const webpack = require('webpack');
const path = require('path');

const isDev = process.env.NODE_ENV !== "production";

const plugins = [
  new webpack.DefinePlugin({
    '__DEV__': process.env.NODE_ENV === 'production',
    'process.env': {
      NODE_ENV: JSON.stringify(process.env.NODE_ENV || 'development')
    }
  })
];

if (isDev) {
  plugins.push(new webpack.HotModuleReplacementPlugin());
  plugins.push(new webpack.NoEmitOnErrorsPlugin());
}

const commons = {
  output: {
    path: path.resolve(__dirname, "../public/bundle/"),
    publicPath: "/assets/bundle/",
    filename: "[name].js",
    library: "[name]",
    libraryTarget: "umd"
  },
  entry: {
    Izanami: "./src/izanami.js"
  },
  resolve: {
    extensions: ["*", ".js", ".css", ".scss"]
  },
  devServer: {
    port: process.env.DEV_SERVER_PORT || 3333,
    firewall: !isDev
  },
  module: {
    rules: [
      {
        test: /\.js|\.jsx|\.es6$/,
        exclude: /node_modules/,
        use: ["babel-loader"]
      },
      {
        test: /\.scss$/i,
        use: ["style-loader", "css-loader", "sass-loader"]
      },
      {
        test: /\.css$/i,
        exclude: /\.useable\.css$/,
        use: ["style-loader", "css-loader"]
      },
      {
        test: /\.useable\.css$/i,
        use: ["style-loader/useable", "css-loader"]
      }
    ]
  },
  plugins: plugins
};

if (isDev) {
  module.exports = {
    ...commons,
    devtool: "inline-source-map",
    mode: "development"
  };
} else {
  module.exports = {
    ...commons,
    optimization: {minimize: true},
    devtool: false,
    mode: "production"
  };
}
