package com.example.navi_app.components

import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.base.event.Event

/** Rendering is native; business commands and events use the page-scoped NavigationModule. */
class NavigationMapView : DeclarativeBaseView<Attr, Event>() {
    override fun createAttr() = Attr()
    override fun createEvent() = Event()
    override fun viewName() = "KRNavigationMapView"
}

fun ViewContainer<*, *>.NavigationMap(init: NavigationMapView.() -> Unit) = addChild(NavigationMapView(), init)
