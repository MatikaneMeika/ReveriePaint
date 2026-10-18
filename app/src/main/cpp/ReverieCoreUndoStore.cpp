/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#include "ReverieCoreUndoStore.h"
#include "ReverieCrashHandler.h"

#include <kundo2stack.h>
#include <cstdio>

ReverieUndoStore::ReverieUndoStore()
    : m_undoStack(new KUndo2Stack)
{
    // 与 KisSurrogateUndoStore 一致: 直连转发避免信号排队 (BUG:447985)
    connect(m_undoStack, SIGNAL(indexChanged(int)), this, SIGNAL(historyStateChanged()), Qt::DirectConnection);
}

ReverieUndoStore::~ReverieUndoStore()
{
    // 析构前断开信号, 避免 indexChanged 在销毁途中继续转发
    disconnect(m_undoStack, SIGNAL(indexChanged(int)), this, SIGNAL(historyStateChanged()));
    delete m_undoStack;
}

const KUndo2Command* ReverieUndoStore::presentCommand()
{
    return m_undoStack->command(m_undoStack->index() - 1);
}

void ReverieUndoStore::undoLastCommand()
{
    m_undoStack->undo();
}

void ReverieUndoStore::addCommand(KUndo2Command *cmd)
{
    if (!cmd) return;
    m_undoStack->push(cmd);
}

void ReverieUndoStore::beginMacro(const KUndo2MagicString& macroName)
{
    m_undoStack->beginMacro(macroName);
}

void ReverieUndoStore::endMacro()
{
    m_undoStack->endMacro();
}

void ReverieUndoStore::purgeRedoState()
{
    m_undoStack->purgeRedoState();
}

void ReverieUndoStore::undo()
{
    if (m_undoStack && m_undoStack->canUndo()) {
        char message[64];
        std::snprintf(message, sizeof(message), "undo index=%d/%d",
                      m_undoStack->index(), m_undoStack->count());
        reverie_crash_handler_add_breadcrumb("HistoryNative", message);
        m_undoStack->undo();
    }
}

void ReverieUndoStore::redo()
{
    if (m_undoStack && m_undoStack->canRedo()) {
        char message[64];
        std::snprintf(message, sizeof(message), "redo index=%d/%d",
                      m_undoStack->index(), m_undoStack->count());
        reverie_crash_handler_add_breadcrumb("HistoryNative", message);
        m_undoStack->redo();
    }
}

void ReverieUndoStore::clear()
{
    m_undoStack->clear();
}

void ReverieUndoStore::setUndoLimit(int limit)
{
    m_undoStack->setUndoLimit(limit);
}

bool ReverieUndoStore::canUndo() const
{
    return m_undoStack && m_undoStack->canUndo();
}

bool ReverieUndoStore::canRedo() const
{
    return m_undoStack && m_undoStack->canRedo();
}

int ReverieUndoStore::count() const
{
    return m_undoStack ? m_undoStack->count() : 0;
}

int ReverieUndoStore::index() const
{
    return m_undoStack ? m_undoStack->index() : 0;
}
