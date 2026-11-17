#include "modulesproxymodel.h"
#include <QCoreApplication>
#include <QStandardItemModel>

int main(int argc, char **argv)
{
    QCoreApplication app(argc, argv);
    QStandardItemModel source(1, 1);
    ModulesProxyModel model;
    model.setSourceModel(&source);
    if (model.filterAcceptsRow(0, QModelIndex())) {
        return 1;
    }
    return 0;
}
