const webpack = require('webpack');
const path = require('path');

const plugins = [
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
        path: path.resolve(__dirname, '../src/main/resources/public/javascripts/bundle/'),
        publicPath: '/javascripts/bundle/',
        filename: '[name].js',
        library: '[name]',
        libraryTarget: 'umd'
    },
    entry: {
        Izanami: './src/index.js'
    },
    resolve: {
        extensions: ['*', '.js', '.css', '.scss']
    },
    devServer: {
        port: process.env.DEV_SERVER_PORT || 3333
    },
    module: {
        loaders: [
            {
                test: /\.js|\.jsx|\.es6$/,
                exclude: /node_modules/,
                loader: 'babel-loader'
            },
            {
                test: /node_modules\/auth0-lock\/.*\.js$/,
                loaders: [
                    'transform-loader/cacheable?brfs',
                    'transform-loader/cacheable?packageify'
                ]
            },
            {
                test: /node_modules\/auth0-lock\/.*\.ejs$/,
                loader: 'transform-loader/cacheable?ejsify'
            },
            {
                test: /\.woff(2)?(\?v=[0-9]\.[0-9]\.[0-9])?$/,
                loader: 'url-loader?limit=10000&minetype=application/font-woff'
            },
            {
                test: /\.(ttf|eot|svg)(\?v=[0-9]\.[0-9]\.[0-9])?$/,
                loader: 'file-loader'
            },
            {
                test: /\.json$/,
                loader: 'json-loader'
            },
            {
                test: /\.scss$/,
                loaders: ['style-loader', 'css-loader', 'sass-loader']
            },
            {
                test: /\.css$/,
                exclude: /\.useable\.css$/,
                loader: 'style-loader!css-loader'
            },
            {
                test: /\.useable\.css$/,
                loader: 'style-loader/useable!css-loader'
            }
        ]
    },
    plugins: plugins
};
