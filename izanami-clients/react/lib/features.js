'use strict';

Object.defineProperty(exports, "__esModule", {
  value: true
});
exports.FeatureProvider = exports.Feature = exports.Disabled = exports.Enabled = undefined;

var _createClass = function () { function defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } } return function (Constructor, protoProps, staticProps) { if (protoProps) defineProperties(Constructor.prototype, protoProps); if (staticProps) defineProperties(Constructor, staticProps); return Constructor; }; }();

require('es6-shim');

require('whatwg-fetch');

var _esSymbol = require('es-symbol');

var _esSymbol2 = _interopRequireDefault(_esSymbol);

var _react = require('react');

var _react2 = _interopRequireDefault(_react);

var _propTypes = require('prop-types');

var _propTypes2 = _interopRequireDefault(_propTypes);

var _deepEqual = require('deep-equal');

var _deepEqual2 = _interopRequireDefault(_deepEqual);

var _deepmerge = require('deepmerge');

var _deepmerge2 = _interopRequireDefault(_deepmerge);

var _lodash = require('lodash');

var _lodash2 = _interopRequireDefault(_lodash);

var _api = require('./api');

var Api = _interopRequireWildcard(_api);

function _interopRequireWildcard(obj) { if (obj && obj.__esModule) { return obj; } else { var newObj = {}; if (obj != null) { for (var key in obj) { if (Object.prototype.hasOwnProperty.call(obj, key)) newObj[key] = obj[key]; } } newObj.default = obj; return newObj; } }

function _interopRequireDefault(obj) { return obj && obj.__esModule ? obj : { default: obj }; }

function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

function _possibleConstructorReturn(self, call) { if (!self) { throw new ReferenceError("this hasn't been initialised - super() hasn't been called"); } return call && (typeof call === "object" || typeof call === "function") ? call : self; }

function _inherits(subClass, superClass) { if (typeof superClass !== "function" && superClass !== null) { throw new TypeError("Super expression must either be null or a function, not " + typeof superClass); } subClass.prototype = Object.create(superClass && superClass.prototype, { constructor: { value: subClass, enumerable: false, writable: true, configurable: true } }); if (superClass) Object.setPrototypeOf ? Object.setPrototypeOf(subClass, superClass) : subClass.__proto__ = superClass; }

if (!window.Symbol) {
  window.Symbol = _esSymbol2.default;
}

var Enabled = exports.Enabled = function (_Component) {
  _inherits(Enabled, _Component);

  function Enabled() {
    _classCallCheck(this, Enabled);

    return _possibleConstructorReturn(this, (Enabled.__proto__ || Object.getPrototypeOf(Enabled)).apply(this, arguments));
  }

  _createClass(Enabled, [{
    key: 'render',
    value: function render() {
      return this.props.children;
    }
  }]);

  return Enabled;
}(_react.Component);

var Disabled = exports.Disabled = function (_Component2) {
  _inherits(Disabled, _Component2);

  function Disabled() {
    _classCallCheck(this, Disabled);

    return _possibleConstructorReturn(this, (Disabled.__proto__ || Object.getPrototypeOf(Disabled)).apply(this, arguments));
  }

  _createClass(Disabled, [{
    key: 'render',
    value: function render() {
      return this.props.children;
    }
  }]);

  return Disabled;
}(_react.Component);

var Feature = exports.Feature = function (_Component3) {
  _inherits(Feature, _Component3);

  function Feature() {
    var _ref;

    var _temp, _this3, _ret;

    _classCallCheck(this, Feature);

    for (var _len = arguments.length, args = Array(_len), _key = 0; _key < _len; _key++) {
      args[_key] = arguments[_key];
    }

    return _ret = (_temp = (_this3 = _possibleConstructorReturn(this, (_ref = Feature.__proto__ || Object.getPrototypeOf(Feature)).call.apply(_ref, [this].concat(args))), _this3), _this3.state = {
      features: {}
    }, _this3.onFeaturesChanged = function (_ref2) {
      var features = _ref2.features;

      if (!(0, _deepEqual2.default)(_this3.state.features, features)) {
        _this3.setState({ features: features });
      }
    }, _temp), _possibleConstructorReturn(_this3, _ret);
  }

  _createClass(Feature, [{
    key: 'componentDidMount',
    value: function componentDidMount() {
      var fetchFrom = this.context.__fetchFrom;
      if (fetchFrom) {
        Api.register(fetchFrom, this.onFeaturesChanged);
      }
    }
  }, {
    key: 'componentWillUnmount',
    value: function componentWillUnmount() {
      var fetchFrom = this.context.__fetchFrom;
      if (fetchFrom) {
        Api.unregister(fetchFrom, this.onFeaturesChanged);
      }
    }
  }, {
    key: 'render',
    value: function render() {
      var children = this.props.children;
      var path = this.props.path.replace(/:/g, '.');
      var features = (0, _deepmerge2.default)(this.context.__mergedFeatures, this.state.features);
      var value = _lodash2.default.get(features, path) || { active: false };
      console.log('Value', features, path);
      var isActive = value.active;
      var childrenArray = Array.isArray(children) ? children : [children];
      var enabledChildren = childrenArray.filter(function (c) {
        return c.type === Enabled;
      });
      var disabledChildren = childrenArray.filter(function (c) {
        return c.type === Disabled;
      });
      var debug = !!this.context.__debug || this.props.debug;
      if (isActive && enabledChildren.length > 0) {
        if (debug) console.log('feature \'' + path + '\' is enabled, rendering first <Enabled /> component');
        if (debug) {
          return _react2.default.createElement(
            'div',
            { className: 'izanami-feature-enabled', title: 'Experiment ' + path + ':\xA0variant is ' + value, style: { position: 'relative', outline: '1px solid green' } },
            _react2.default.createElement(
              'span',
              { style: { padding: 2, color: 'white', backgroundColor: 'green', position: 'absolute', top: -17, left: -1, zIndex: 100000 } },
              'Feature ',
              _react2.default.createElement(
                'span',
                { style: { fontWeight: 'bold' } },
                path
              ),
              ' is enabled'
            ),
            enabledChildren[0]
          );
        }
        return enabledChildren[0];
      } else if (!isActive && disabledChildren.length > 0) {
        if (debug) console.log('feature \'' + path + '\' is disabled, rendering first <Disabled /> component');
        if (debug) {
          return _react2.default.createElement(
            'div',
            { className: 'izanami-feature-disabled', title: 'Experiment ' + path + ':\xA0variant is ' + value, style: { position: 'relative', outline: '1px solid grey' } },
            _react2.default.createElement(
              'span',
              { style: { padding: 2, color: 'white', backgroundColor: 'grey', position: 'absolute', top: -17, left: -1, zIndex: 100000 } },
              'Feature ',
              _react2.default.createElement(
                'span',
                { style: { fontWeight: 'bold' } },
                path
              ),
              ' is disabled'
            ),
            disabledChildren[0]
          );
        }
        return disabledChildren[0];
      } else if (isActive) {
        if (debug) console.log('feature \'' + path + '\' is enabled, rendering first child');
        if (childrenArray.length > 1) {
          console.warn('You have to provide only one child to <Feature /> unless it\'s <Enabled /> and <Disabled /> used together.');
        }
        if (debug) {
          return _react2.default.createElement(
            'div',
            { className: 'izanami-feature-enabled', title: 'Experiment ' + path + ':\xA0variant is ' + value, style: { position: 'relative', outline: '1px solid green' } },
            _react2.default.createElement(
              'span',
              { style: { padding: 2, color: 'white', backgroundColor: 'green', position: 'absolute', top: -17, left: -1, zIndex: 100000 } },
              'Feature ',
              _react2.default.createElement(
                'span',
                { style: { fontWeight: 'bold' } },
                path
              ),
              ' is enabled'
            ),
            childrenArray[0]
          );
        }
        return childrenArray[0];
      } else {
        if (debug) console.log('feature \'' + path + '\' is disabled, rendering nothing');
        if (debug) {
          return _react2.default.createElement(
            'div',
            { className: 'izanami-feature-disabled', title: 'Experiment ' + path + ':\xA0variant is ' + value, style: { position: 'relative', outline: '1px solid grey' } },
            _react2.default.createElement(
              'span',
              { style: { padding: 2, color: 'white', backgroundColor: 'grey', position: 'absolute', top: -17, left: -1, zIndex: 100000 } },
              'Feature ',
              _react2.default.createElement(
                'span',
                { style: { fontWeight: 'bold' } },
                path
              ),
              ' is disabled'
            )
          );
        }
        return null;
      }
    }
  }]);

  return Feature;
}(_react.Component);

Feature.contextTypes = {
  __mergedFeatures: _propTypes2.default.object,
  __fetchFrom: _propTypes2.default.string,
  __debug: _propTypes2.default.bool
};
Feature.propTypes = {
  path: _propTypes2.default.string.isRequired,
  debug: _propTypes2.default.bool
};
Feature.defaultProps = {
  debug: false
};

var FeatureProvider = exports.FeatureProvider = function (_Component4) {
  _inherits(FeatureProvider, _Component4);

  function FeatureProvider() {
    var _ref3;

    var _temp2, _this4, _ret2;

    _classCallCheck(this, FeatureProvider);

    for (var _len2 = arguments.length, args = Array(_len2), _key2 = 0; _key2 < _len2; _key2++) {
      args[_key2] = arguments[_key2];
    }

    return _ret2 = (_temp2 = (_this4 = _possibleConstructorReturn(this, (_ref3 = FeatureProvider.__proto__ || Object.getPrototypeOf(FeatureProvider)).call.apply(_ref3, [this].concat(args))), _this4), _this4.state = {
      features: _this4.props.features,
      fallback: _this4.props.fallback,
      debug: _this4.props.debug
    }, _temp2), _possibleConstructorReturn(_this4, _ret2);
  }

  _createClass(FeatureProvider, [{
    key: 'getChildContext',
    value: function getChildContext() {
      var features = (0, _deepmerge2.default)(this.state.fallback, this.state.features);
      return {
        __debug: this.state.debug,
        __features: this.state.features,
        __fallback: this.state.fallback,
        __mergedFeatures: (0, _deepmerge2.default)(this.state.fallback, this.state.features),
        __fetchFrom: this.props.fetchFrom
      };
    }
  }, {
    key: 'componentWillReceiveProps',
    value: function componentWillReceiveProps(nextProps) {
      if (!(0, _deepEqual2.default)(nextProps.features, this.props.features)) {
        this.setState({ features: nextProps.features });
      }
      if (!(0, _deepEqual2.default)(nextProps.fallback, this.props.fallback)) {
        this.setState({ fallback: nextProps.fallback });
      }
      if (nextProps.debug !== this.props.debug) {
        this.setState({ debug: nextProps.debug });
      }
    }
  }, {
    key: 'render',
    value: function render() {
      return this.props.children || null;
    }
  }]);

  return FeatureProvider;
}(_react.Component);

FeatureProvider.childContextTypes = {
  __features: _propTypes2.default.object,
  __fallback: _propTypes2.default.object,
  __mergedFeatures: _propTypes2.default.object,
  __debug: _propTypes2.default.bool,
  __fetchFrom: _propTypes2.default.string
};
FeatureProvider.propTypes = {
  features: _propTypes2.default.object.isRequired,
  fallback: _propTypes2.default.object,
  fetchFrom: _propTypes2.default.string,
  debug: _propTypes2.default.bool
};
FeatureProvider.defaultProps = {
  fallback: {}
};