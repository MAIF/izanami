const webpack = require('webpack');
const path = require('path');

const isDev = process.env.NODE_ENV !== 'production';

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
        path: path.resolve(__dirname),
        publicPath: '/javascripts/bundle/',
        filename: '[name].js',
        library: 'Izanami',
        libraryTarget: 'umd'
    },
    entry: {
        Izanami: './src/index.js',
        '../example-spring/src/main/resources/public/javascripts/bundle/Izanami': './src/index.js',
        '../example-play/public/javascripts/bundle/Izanami': './src/index.js'
    },
    resolve: {
        extensions: ['*', '.js', '.css', '.scss']
    },
    devServer: {
        port: process.env.DEV_SERVER_PORT || 3334,
        firewall: !isDev
    },
    module: {
        rules: [
            {
                test: /\.js|\.jsx|\.es6$/,
                exclude: /node_modules/,
                use: [ 'babel-loader' ]
            },
            {
                test: /\.scss$/,
                use: [ "style-loader", "css-loader?url=false", "sass-loader" ]
            },
            {
                test: /\.css$/,
                exclude: /\.useable\.css$/,
                use: [ "style-loader", "css-loader?url=false" ],
            },
            {
                test: /\.useable\.css$/,
                use: [ "style-loader/useable", "css-loader?url=false" ],
            }
        ]
    },
    plugins: plugins
};


if (isDev) {
    module.exports = {...commons, devtool: 'inline-source-map', mode: 'development'};
} else {
    module.exports = {...commons, optimization: {minimize: true}, devtool: false, mode: 'production'};
}


