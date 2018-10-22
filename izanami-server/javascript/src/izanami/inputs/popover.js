import React, { Component } from 'react';
import $ from 'jquery';

export function popover({options, state = 'hide', closeAfter, onClose, onClick}) {
  return {
    ref: r => {
        $(r).popover(options)
          .on('shown.bs.popover', function (eventShown) {
            const $popup = $('#' + $(eventShown.target).attr('aria-describedby'));
            $popup.find('.cancel').click(function (e) {
              $popup.popover('hide');
              if (onClose) {
                onClose();
              }
            });
            $popup.find('.click').click(function (e) {
              $popup.popover('hide');
              if (onClick) {
                  onClick();
              }
            });
          })
          .popover(state);
        if (closeAfter) {
          setTimeout(() => {
            $(r).popover('destroy')
          }, closeAfter);
        }
    }
  }
}