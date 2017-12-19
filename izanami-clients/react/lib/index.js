'use strict';

Object.defineProperty(exports, "__esModule", {
  value: true
});
exports.IzanamiProvider = exports.Api = undefined;

var _createClass = function () { function defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } } return function (Constructor, protoProps, staticProps) { if (protoProps) defineProperties(Constructor.prototype, protoProps); if (staticProps) defineProperties(Constructor, staticProps); return Constructor; }; }();

var _features2 = require('./features');

Object.keys(_features2).forEach(function (key) {
  if (key === "default" || key === "__esModule") return;
  Object.defineProperty(exports, key, {
    enumerable: true,
    get: function get() {
      return _features2[key];
    }
  });
});

var _experiments2 = require('./experiments');

Object.keys(_experiments2).forEach(function (key) {
  if (key === "default" || key === "__esModule") return;
  Object.defineProperty(exports, key, {
    enumerable: true,
    get: function get() {
      return _experiments2[key];
    }
  });
});

var _react = require('react');

var _react2 = _interopRequireDefault(_react);

var _api = require('./api');

var Api = _interopRequireWildcard(_api);

var _Api = _interopRequireWildcard(_api);

var _propTypes = require('prop-types');

var _propTypes2 = _interopRequireDefault(_propTypes);

function _interopRequireWildcard(obj) { if (obj && obj.__esModule) { return obj; } else { var newObj = {}; if (obj != null) { for (var key in obj) { if (Object.prototype.hasOwnProperty.call(obj, key)) newObj[key] = obj[key]; } } newObj.default = obj; return newObj; } }

function _interopRequireDefault(obj) { return obj && obj.__esModule ? obj : { default: obj }; }

function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

function _possibleConstructorReturn(self, call) { if (!self) { throw new ReferenceError("this hasn't been initialised - super() hasn't been called"); } return call && (typeof call === "object" || typeof call === "function") ? call : self; }

function _inherits(subClass, superClass) { if (typeof superClass !== "function" && superClass !== null) { throw new TypeError("Super expression must either be null or a function, not " + typeof superClass); } subClass.prototype = Object.create(superClass && superClass.prototype, { constructor: { value: subClass, enumerable: false, writable: true, configurable: true } }); if (superClass) Object.setPrototypeOf ? Object.setPrototypeOf(subClass, superClass) : subClass.__proto__ = superClass; }

exports.Api = _Api;

var IzanamiProvider = exports.IzanamiProvider = function (_Component) {
  _inherits(IzanamiProvider, _Component);

  function IzanamiProvider(args) {
    _classCallCheck(this, IzanamiProvider);

    var _this = _possibleConstructorReturn(this, (IzanamiProvider.__proto__ || Object.getPrototypeOf(IzanamiProvider)).call(this, args));

    _this.state = {
      loading: false,
      fetched: {}
    };

    _this.onDataLoaded = function (data) {
      _this.setState({
        loading: false,
        fetched: {
          features: data.features || _this.props.features,
          featuresFallback: data.featuresFallback || _this.props.featuresFallback,
          experiments: data.experiments || _this.props.experiments,
          experimentsFallback: data.experimentsFallback || _this.props.experimentsFallback,
          debug: data.debug || !!_this.props.debug
        }
      });
    };

    return _this;
  }

  _createClass(IzanamiProvider, [{
    key: 'componentDidMount',
    value: function componentDidMount() {
      if (this.props.fetchFrom) {
        console.log(this.featuresCallbacks);
        Api.register(this.props.fetchFrom, this.onDataLoaded);
        this.setState({ loading: true });
        Api.izanamiReload(this.props.fetchFrom, this.props.fetchHeaders);
      }
    }
  }, {
    key: 'componentWillUnmount',
    value: function componentWillUnmount() {
      if (this.props.fetchFrom) {
        Api.unregister(this.props.fetchFrom, this.onDataLoaded);
      }
    }
  }, {
    key: 'render',
    value: function render() {
      if (this.props.fetchFrom) {
        var _state$fetched = this.state.fetched,
            debug = _state$fetched.debug,
            features = _state$fetched.features,
            featuresFallback = _state$fetched.featuresFallback,
            experiments = _state$fetched.experiments,
            experimentsFallback = _state$fetched.experimentsFallback;

        console.log('Refreshing', features);
        return _react2.default.createElement(
          _features2.FeatureProvider,
          { debug: debug, features: features || {}, fallback: featuresFallback, fetchFrom: this.props.fetchFrom },
          _react2.default.createElement(
            _experiments2.ExperimentsProvider,
            { debug: debug, experiments: experiments || {}, experimentsFallback: experimentsFallback },
            this.props.children
          )
        );
      } else {
        var _props = this.props,
            _debug = _props.debug,
            _features = _props.features,
            _featuresFallback = _props.featuresFallback,
            _experiments = _props.experiments,
            _experimentsFallback = _props.experimentsFallback,
            children = _props.children;

        return _react2.default.createElement(
          _features2.FeatureProvider,
          { debug: _debug, features: _features, fallback: _featuresFallback },
          _react2.default.createElement(
            _experiments2.ExperimentsProvider,
            { debug: _debug, experiments: _experiments, experimentsFallback: _experimentsFallback },
            children
          )
        );
      }
    }
  }]);

  return IzanamiProvider;
}(_react.Component);

IzanamiProvider.propTypes = {
  features: _propTypes2.default.object,
  featuresFallback: _propTypes2.default.object,
  experiments: _propTypes2.default.object,
  experimentsFallback: _propTypes2.default.object,
  debug: _propTypes2.default.bool,
  fetchFrom: _propTypes2.default.string,
  fetchData: _propTypes2.default.func,
  fetchHeaders: _propTypes2.default.object,
  loading: _propTypes2.default.func
};
IzanamiProvider.defaultProps = {
  featuresFallback: {},
  experimentsFallback: {},
  debug: false,
  fetchHeaders: {},
  loading: function loading() {
    return null;
  }
};