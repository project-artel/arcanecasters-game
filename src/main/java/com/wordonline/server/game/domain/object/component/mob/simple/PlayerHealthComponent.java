package com.wordonline.server.game.domain.object.component.mob.simple;

import com.wordonline.server.game.domain.AttackInfo;
import com.wordonline.server.game.domain.PlayerData;
import com.wordonline.server.game.domain.object.GameObject;
import com.wordonline.server.game.domain.object.component.mob.Mob;
import com.wordonline.server.game.dto.Master;

public class PlayerHealthComponent extends Mob {
    private PlayerData playerData;

    @Override
    public void applyDamage(AttackInfo attackInfo) {
        super.applyDamage(attackInfo);
        playerData.hp = hp;
    }

    @Override
    public void setQaHealth(int health) {
        super.setQaHealth(health);
        playerData.hp = hp;
    }

    @Override
    public void start() {}

    @Override
    public void update() {
        super.update();
    }

    @Override
    public void onDestroy() {

    }

    @Override
    public void onDeath() {
        getGameContext().setLoser(gameObject.getMaster());
    }

    public PlayerHealthComponent(GameObject gameObject, int maxHealth) {
        super(gameObject, maxHealth, 0);

        if (gameObject.getMaster() == Master.LeftPlayer) {
            playerData = getGameContext().getGameSessionData().leftPlayerData;
        }
        if (gameObject.getMaster() == Master.RightPlayer) {
            playerData = getGameContext().getGameSessionData().rightPlayerData;
        }
        playerData.hp = maxHealth;
    }
}
