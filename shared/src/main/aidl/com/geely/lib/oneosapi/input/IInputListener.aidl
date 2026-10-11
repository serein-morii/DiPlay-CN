// Reverse-engineered from the head unit's com.geely.inputservice; transaction order must match.
package com.geely.lib.oneosapi.input;

interface IInputListener {
    void onKeyCodeEvent(int keyCode, int event, int softKeyFunction);
    void onShortClick(int keyCode, int softKeyFunction);
    void onHoldingPressStarted(int keyCode, int softKeyFunction);
    void onHoldingPressStopped(int keyCode, int softKeyFunction);
    void onLongPressTriggered(int keyCode, int softKeyFunction);
    void onDoubleClick(int keyCode, int softKeyFunction);
}
